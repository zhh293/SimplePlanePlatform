"""Open the operator's real management console using the dedicated Windows profile."""
import ctypes
import json
import os
from pathlib import Path
import subprocess
from urllib.parse import urlsplit

from playwright.sync_api import Error, sync_playwright


ORIGIN = "https://212.64.14.155"
DIRECTORY = Path(os.environ["LOCALAPPDATA"]) / "SimplePlane" / "SubscriptionAdmin"


def sign_in(page):
    page.goto(ORIGIN + "/admin/grants", timeout=45000)
    page.wait_for_selector('input[name="username"], body[data-console], input[name="subject"]', timeout=30000)
    if page.locator('input[name="username"]').is_visible():
        parsed = urlsplit(page.url)
        if (parsed.scheme, parsed.netloc, parsed.path) != ("https", "212.64.14.155", "/login"):
            raise RuntimeError("Unexpected identity provider")
        script = ("$ErrorActionPreference='Stop'; "
                  "$c=Import-Clixml -LiteralPath (Join-Path $env:LOCALAPPDATA 'SimplePlane/SubscriptionAdmin/zhh.credential.xml'); "
                  "if($c.UserName -ne 'zhh'){exit 1}; "
                  "[Console]::Write($c.GetNetworkCredential().Password)")
        secret = subprocess.check_output(["powershell.exe", "-NoProfile", "-Command", script],
                                         creationflags=subprocess.CREATE_NO_WINDOW, timeout=15).decode("ascii")
        page.locator('input[name="username"]').fill("zhh")
        page.locator('input[name="password"]').fill(secret)
        del secret
        page.locator('button[type="submit"]').click()
    page.locator('body[data-console], input[name="subject"]').first.wait_for(timeout=30000)
    parsed = urlsplit(page.url)
    if (parsed.scheme, parsed.netloc, parsed.path) != ("https", "212.64.14.155", "/admin/grants"):
        raise RuntimeError("Unexpected management page")


def main():
    runtime = json.loads((DIRECTORY / "runtime.json").read_text(encoding="utf-8-sig"))
    with sync_playwright() as pw:
        ctx = pw.firefox.launch_persistent_context(str(DIRECTORY / "firefox"), executable_path=runtime["firefox"],
                headless=False, no_viewport=True, firefox_user_prefs={"network.proxy.type":0})
        try:
            page = ctx.pages[0] if ctx.pages else ctx.new_page()
            sign_in(page)
            page.bring_to_front()
            (DIRECTORY / "last-opened.txt").write_text("Remote administrator form verified", encoding="utf-8")
            while ctx.pages:
                try:
                    ctx.pages[0].wait_for_timeout(900)
                except Error:
                    if not ctx.pages:
                        break
        finally:
            ctx.close()


if __name__ == "__main__":
    try:
        main()
    except Exception:
        ctypes.windll.user32.MessageBoxW(0, "请先查看是否已打开管理窗口。若登录失败，可使用专用 Firefox 配置手动登录。",
                                         "SimplePlane 管理后台", 0x30)
