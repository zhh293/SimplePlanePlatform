"""Exercise the isolated OIDC, consent, SQL entitlement and device-signing flow.

Use a private Firefox profile which already trusts this canary's root. The script
never disables browser or API certificate verification or logs device secrets.
"""
import argparse
import base64
import http.client
import json
from pathlib import Path
import ssl
import time
from urllib.parse import urlsplit

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from playwright.sync_api import sync_playwright


HOST = "212.64.14.155"
ORIGIN = "https://" + HOST


def public(key):
    return base64.b64encode(key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()


def sign(key, message):
    return base64.b64encode(key.sign(message.encode(), ec.ECDSA(hashes.SHA256()))).decode()


class Device:
    def __init__(self, ca):
        self.context = ssl.create_default_context(cafile=str(ca))
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.id = None

    def post(self, path, body, expected=200):
        connection = http.client.HTTPSConnection(HOST, context=self.context, timeout=12)
        try:
            connection.request("POST", "/device/" + path, json.dumps(body), {"Content-Type": "application/json"})
            response = connection.getresponse()
            data = response.read()
            if response.status != expected:
                raise RuntimeError(f"Device {path.rsplit('/', 1)[-1]} returned {response.status}, expected {expected}")
            return json.loads(data) if expected == 200 else None
        finally:
            connection.close()

    def start(self):
        pair = self.post("pair", {"name": "Browser canary", "publicKey": public(self.key)})
        assert pair["verificationUri"] == ORIGIN + "/account/pair/" + pair["userCode"]
        self.pair = pair
        return pair

    def poll(self):
        secret = self.pair["deviceSecret"]
        return self.post("poll", {"deviceSecret": secret, "signature": sign(self.key, "simpleplane-pair-v1\n" + secret)})

    def proof(self, tls_key):
        return self.proof_for_public(public(tls_key))

    def proof_for_public(self, pk):
        challenge = self.post(self.id + "/challenge", {})["challenge"]
        return {"challenge": challenge, "tlsPublicKey": pk,
                "signature": sign(self.key, f"simpleplane-manifest-v1\n{self.id}\n{challenge}\n{pk}")}


def stage(name):
    print(name, flush=True)


def test_viewer(browser, credentials, device, work):
    viewer = browser.new_context()
    try:
        page = viewer.new_page()
        page.goto(ORIGIN + "/account/devices")
        page.locator('input[name="username"]').fill(credentials["username"])
        page.locator('input[name="password"]').fill(credentials["password"])
        page.locator('button[type="submit"]').click()
        page.locator("h1").filter(has_text="我的设备").wait_for()
        assert not page.locator("article").count()
        assert page.evaluate("async () => (await fetch('/admin/grants')).status") == 403
        status = page.evaluate("""async id => {
            const csrf = document.querySelector('input[name="_csrf"]').value;
            return (await fetch('/account/devices/'+id+'/unbind', {method:'POST',
                        body:new URLSearchParams({_csrf:csrf})})).status;
        }""", device.id)
        assert status == 404
        candidate = Device(work / "root.crt")
        pair = candidate.start()
        page.goto(pair["verificationUri"])
        with page.expect_response(lambda r: r.request.method == "POST" and r.url == pair["verificationUri"]) as response:
            page.locator('button[value="allow"]').click()
        assert response.value.status == 403 and candidate.poll()["status"] == "pending"
        stage("PASS second OIDC account cannot administer, see or unbind another user's device, or enroll without a grant")
    finally:
        viewer.close()


def grant(portal, subject, days=1):
    portal.goto(ORIGIN + "/admin/grants")
    if portal.locator("body[data-console]").count():
        from urllib.parse import quote
        portal.goto(ORIGIN + "/admin/users/" + quote(subject, safe=""))
        if days:
            form = portal.locator('form[action="/admin/grants"]').filter(has=portal.locator("select[name=groupId]"))
            form.locator("select[name=groupId]").select_option("test")
            form.locator("input[name=days]").fill(str(days))
            form.locator("input[name=deviceLimit]").fill("2")
        else:
            form = portal.locator('form[action="/admin/grants"][data-confirm]')
            portal.once("dialog", lambda dialog: dialog.accept())
        form.locator("button").click()
    else:
        for name, value in {"subject": subject, "groupId": "test", "days": str(days), "deviceLimit": "2"}.items():
            portal.locator(f'input[name="{name}"]').fill(value)
        portal.locator("form button").click()
    portal.locator('[role="status"]').wait_for()


def desktop_flow(driver, portal, port, subject):
    import cv2
    import numpy
    browser = driver.chromium.connect_over_cdp(f"http://127.0.0.1:{port}")
    desktop = next(p for c in browser.contexts for p in c.pages if urlsplit(p.url).hostname == "tauri.localhost")
    call = lambda command: desktop.evaluate("cmd => window.__TAURI__.core.invoke(cmd)", command)
    if call("subscription_bound"):
        call("subscription_logout")
    # Allow the per-IP ingress bucket to drain between the API suite and the
    # UI suite. In production, these browsers and devices have user think time.
    time.sleep(8)
    button = desktop.locator("#btnSubscription")
    portal.goto(ORIGIN + "/account/devices")
    previous_devices = set(portal.locator("article code").all_inner_texts())
    # This button lives on the node settings tab of the actual packaged UI.
    if not button.is_visible():
        desktop.locator('.nav-item[data-section="proxy-config"]').click()
    before = desktop.evaluate("window.__TAURI__.core.invoke('get_config')")
    button.click()
    qr = desktop.locator("#subscriptionQr")
    qr.wait_for(state="visible", timeout=20000)
    data = qr.evaluate("c => c.toDataURL().split(',')[1]")
    pixels = cv2.imdecode(numpy.frombuffer(base64.b64decode(data), dtype=numpy.uint8), cv2.IMREAD_GRAYSCALE)
    url, _, _ = cv2.QRCodeDetector().detectAndDecode(pixels)
    code = desktop.locator("#subscriptionCode").inner_text()
    assert url == ORIGIN + "/account/pair/" + code and len(code) == 12
    portal.goto(url)
    assert portal.locator("output").inner_text() == code
    portal.locator('button[value="allow"]').click()
    desktop.locator("#subscriptionLogout").wait_for(state="visible", timeout=25000)
    desktop.wait_for_function("document.querySelector('#subscriptionMessage').textContent.includes('已同步')")
    time.sleep(1)
    assert call("subscription_bound") and call("subscription_sync") == 1
    after = call("get_config")
    assert [n["id"] for n in after["nodes"]] == [n["id"] for n in before["nodes"]] + ["subscription-canary-a"]
    assert before["selected_node_id"] == after["selected_node_id"]
    grant(portal, subject, days=0)
    rejected = desktop.evaluate("""async () => {
        try { await window.__TAURI__.core.invoke('subscription_sync'); return ''; }
        catch (failure) { return String(failure); }
    }""")
    assert "403" in rejected and call("get_config") == after
    grant(portal, subject)
    assert call("subscription_sync") == 1
    desktop.locator("#subscriptionLogout").click()
    desktop.wait_for_function("async () => !(await window.__TAURI__.core.invoke('subscription_bound'))")
    assert call("get_config")["nodes"] == before["nodes"]
    portal.goto(ORIGIN + "/account/devices")
    created = set(portal.locator("article code").all_inner_texts()) - previous_devices
    assert len(created) == 1
    portal.locator("article").filter(has_text=created.pop()).locator("button").click()
    stage("PASS Windows desktop rendered QR decoded, browser confirmation, OS keyring, import, repeat sync, logout and manual node preservation")
    stage("PASS admin expiry denied desktop renewal, last snapshot preserved, regrant recovered")


def exercise(args):
    work = args.run
    credentials = json.loads((work / "browser.json").read_text())
    with sync_playwright() as driver:
        context = driver.firefox.launch_persistent_context(
            str(work / "firefox"), executable_path=str(args.firefox), headless=True,
            firefox_user_prefs={"security.enterprise_roots.enabled": False, "network.proxy.type": 0})
        try:
            context.clear_cookies()
            page = context.new_page()
            page.set_default_timeout(15000)
            page.goto(ORIGIN + "/admin/grants")
            page.locator('input[name="username"]').fill(credentials["username"])
            page.locator('input[name="password"]').fill(credentials["password"])
            page.locator('button[type="submit"]').click()
            page.locator('body[data-console], input[name="subject"]').first.wait_for()
            assert urlsplit(page.url).path == "/admin/grants"
            stage("PASS Firefox TLS, OIDC login, SDK callback and administrator session")
            grant(page, credentials["subject"])
            page.goto(ORIGIN + "/account/devices")
            # Only this ephemeral test account's stale automated devices, after an interrupted run.
            stale = page.locator("article").filter(has=page.get_by_role("heading", name="Browser canary", exact=True))
            if args.desktop_port:
                # The temporary canary account is exclusively owned by this run.
                stale = page.locator("article")
            while stale.count():
                stale.first.locator("button").click()
            device = Device(work / "root.crt")
            for decision in ["deny", "allow"]:
                pair = device.start()
                assert device.poll()["status"] == "pending"
                page.goto(pair["verificationUri"])
                assert page.locator("output").inner_text() == pair["userCode"]
                page.locator(f'button[value="{decision}"]').click()
                page.locator('[role="status"]').wait_for()
                result = device.poll()
                if decision == "deny":
                    assert result["status"] == "denied" and not result.get("deviceId")
                else:
                    assert result["status"] == "approved" and result["deviceId"]
                    device.id = result["deviceId"]
                    assert device.poll()["status"] == "redeemed"
            stage("PASS explicit deny, allow and one-time redemption")
            tls_key = ec.generate_private_key(ec.SECP256R1())
            proof = device.proof(tls_key)
            endpoint = device.id + "/manifest"
            manifest = device.post(endpoint, proof)
            assert manifest["version"] == 1 and 0 < manifest["expiresAt"] - time.time() <= 300
            assert [n["id"] for n in manifest["nodes"]] == ["canary-a"]
            node = manifest["nodes"][0]
            leaf = x509.load_pem_x509_certificate(node["deviceCertificatePem"].encode())
            assert leaf.public_key().public_numbers() == tls_key.public_key().public_numbers()
            device.post(endpoint, proof, 403)
            stage("PASS node group, short-lived certificate bound to device TLS key, replay rejected")
            if "viewer" in credentials:
                test_viewer(context.browser, credentials["viewer"], device, work)
            if args.relay:
                from subscription_canary_traffic import exercise_relay
                exercise_relay(work, node, tls_key, args.relay)
                stage("PASS issued node relayed TCP and UDP")
            if args.remote_key:
                import re
                import subprocess
                run_id = (work / "run-id").read_text().strip()
                assert re.fullmatch(r"sp-sub-[0-9a-f]{12}", run_id)
                remote = "/var/tmp/" + run_id + "/traffic.py"
                cmd = f"nsenter -t $(docker inspect --format '{{{{.State.Pid}}}}' {run_id}-pod) -n /var/tmp/{run_id}/venv/bin/python {remote}"
                ssh = ["ssh", "-i", str(args.remote_key), "-o", "BatchMode=yes", "-o",
                       "IdentitiesOnly=yes", "-o", "StrictHostKeyChecking=yes", "root@" + HOST]
                with subprocess.Popen(ssh + [cmd],
                    stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True) as test:
                    pk = test.stdout.readline().strip()
                    assert len(base64.b64decode(pk, validate=True)) == 91
                    renewal = device.post(endpoint, device.proof_for_public(pk))
                    result, _ = test.communicate(json.dumps(renewal["nodes"][0]) + "\n", timeout=80)
                    if test.returncode or "PASS namespace TCP and UDP echo" not in result:
                        raise RuntimeError("Remote namespace traffic test failed: " + result[-100:])
                stage("PASS actual Java client and node, authorized TCP and UDP in isolated remote network")
            observer = None
            if args.observe_expiry:
                observer = subprocess.Popen(ssh + ["timeout 330 " + cmd + " observe-expiry"],
                    stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
                pk = observer.stdout.readline().strip()
                lease = device.post(endpoint, device.proof_for_public(pk))
                observer.stdin.write(json.dumps(lease["nodes"][0]) + "\n")
                observer.stdin.flush()
                assert observer.stdout.readline().strip() == "ACTIVE"
            page.goto(ORIGIN + "/account/devices")
            row = page.locator("article").filter(has_text=device.id)
            row.locator("button").click()
            row.wait_for(state="detached")
            device.post(endpoint, device.proof(tls_key), 403)
            stage("PASS browser unbind immediately denied fresh device certificate")
            if observer:
                output, _ = observer.communicate(timeout=315)
                assert observer.returncode == 0 and output.startswith("PASS active QUIC closed at expiry")
                stage(output.strip())
            if args.desktop_port:
                desktop_flow(driver, page, args.desktop_port, credentials["subject"])
        finally:
            context.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--firefox", type=Path, required=True)
    parser.add_argument("--relay", type=Path, help="Java local proxy jar for real traffic checks")
    parser.add_argument("--desktop-port", type=int, help="Loopback debug port of isolated Windows test client")
    parser.add_argument("--remote-key", type=Path, help="SSH key for gray host; test through its isolated network")
    parser.add_argument("--observe-expiry", action="store_true", help="Keep the remote connection active across browser unbind")
    try:
        args = parser.parse_args()
        if args.observe_expiry and not args.remote_key:
            parser.error("--observe-expiry requires --remote-key")
        exercise(args)
    except Exception as failure:
        # Browser error details can include single-use codes and OAuth state.
        import traceback
        frame = traceback.extract_tb(failure.__traceback__)
        own = [f for f in frame if Path(f.filename).name == Path(__file__).name]
        import re
        detail = re.sub(r"https?://\S+", "[url]", str(failure).split("Call log:")[0])[:400]
        raise SystemExit(f"FAIL {type(failure).__name__} at line {own[-1].lineno}: {detail}")
