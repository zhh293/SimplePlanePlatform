"""Switch one managed HTTP/3 relay from shared 9090 to required device mTLS."""

import os
from pathlib import Path
import re
import runpy
import sys

shared = runpy.run_path(str(Path(__file__).with_name("shared-listener.py")))
NODES, ROOT = shared["NODES"], shared["ROOT"]
pid, ready, run, write = (shared[name] for name in ("pid", "ready", "run", "write"))


DROP_NAME = "99-simpleplane-device-only.conf"
PROD_PORT = 19090
TEST_PORT = 19091


def listener_command(unit, original_directory):
    main_pid = pid(unit)
    if main_pid <= 1:
        raise RuntimeError("The existing relay must be running")
    proc = Path("/proc", str(main_pid))
    args = proc.joinpath("cmdline").read_bytes().decode().strip("\0").split("\0")
    cp = args.index("-cp") + 1
    current_classpath = args[cp].split(":")
    if (len(current_classpath) != 2 or current_classpath[0] != original_directory
            or args[cp + 1] != "com.proxy.remote.ProxyRemoteServer"):
        raise RuntimeError("Unexpected relay classpath")
    credentials = f"/run/credentials/{unit}.service/"
    frame_option = "-Dsimpleplane.subscriptionFrameKeyFile=" + credentials + "subscription-frame.key"
    ca_option = "-Dsimpleplane.deviceCaFile=" + credentials + "subscription-ca.crt"
    if frame_option not in args or ca_option not in args:
        raise RuntimeError("Expected a running shared subscription listener")
    # Read the effective YAML from the running service's mount namespace.
    config = proc.joinpath("root", original_directory.lstrip("/"), "remote.yml").read_text()
    if not re.search(r"(?m)^host:[ \t]*127\.0\.0\.1[ \t]*$", config):
        raise RuntimeError("Expected an existing loopback listener")
    if not re.search(r"(?m)^transport:[ \t]*http3[ \t]*$", config):
        raise RuntimeError("Expected an existing HTTP/3 listener")
    key = ROOT.joinpath("private/frame.key").read_text().strip()
    if not re.fullmatch(r"[a-f0-9]{32}", key):
        raise RuntimeError("Missing or invalid subscription frame key")
    config = replace_field(config, "cipher", "chacha20", "aes-gcm-v2")
    config = replace_field(config, "cipherKey", "[^\r\n]+", "'" + key + "'")
    return args, cp, frame_option, config


def replace_field(config, field, previous, new_value):
    pattern = rf"^{re.escape(field)}:[ \t]*(?:{previous})[ \t]*$"
    config, count = re.subn(pattern, lambda _: f"{field}: {new_value}", config, flags=re.M)
    if count != 1:
        raise RuntimeError(f"Unexpected {field} setting")
    return config


def test(args, cp, config):
    service = "simpleplane-device-only-acceptance"
    if run("systemctl", "show", service, "-p", "ActiveState", "--value") == "active":
        raise RuntimeError("The device-only candidate is already running")
    free = re.search(r"(?m)^MemAvailable:\s+(\d+) kB$", Path("/proc/meminfo").read_text())
    if not free or int(free[1]) < 196608:
        raise RuntimeError("Insufficient memory headroom for a second relay")
    folder = ROOT / "device-only-candidate"
    folder.mkdir(mode=0o700, exist_ok=True)
    config = replace_field(config, "port", str(PROD_PORT), str(TEST_PORT))
    write(folder / "remote.yml", config.encode())
    run("systemd-run", "--quiet", "--collect", "--unit=" + service,
        "-p", "RuntimeMaxSec=8min", "-p", "MemoryMax=144M", "-p", "CPUQuota=60%",
        args[0], "-XX:ActiveProcessorCount=1", "-Xms12m", "-Xmx48m", "-Xss384k",
        "-Dsimpleplane.deviceCaFile=" + str(ROOT / "private/device.crt"),
        "-cp", str(folder) + ":" + args[cp].split(":")[1], "com.proxy.remote.ProxyRemoteServer")
    try:
        ready(TEST_PORT, pid(service))
    except Exception:
        run("systemctl", "stop", service)
        raise


def restart(unit):
    run("systemctl", "daemon-reload")
    run("systemctl", "restart", unit)
    ready(PROD_PORT, pid(unit))


def activate(unit, drop, args, cp, frame_option, config):
    path = ROOT / "device-only-remote.yml"
    credentials = Path("/run/credentials", unit + ".service")
    if drop.exists() or path.exists():
        raise RuntimeError("Device-only configuration already exists")
    args[cp] = str(credentials) + ":" + args[cp].split(":")[1]
    args.remove(frame_option)
    if any(re.search(r'[\r\n"\\$%]', arg) for arg in args):
        raise RuntimeError("Unsupported relay command arguments")
    checked = replace_field(config, "port", str(PROD_PORT), str(PROD_PORT))
    write(path, checked.encode())
    definition = "[Service]\nLoadCredential=remote.yml:" + str(path)
    definition += "\nExecStart=\nExecStart=" + " ".join(f'"{arg}"' for arg in args) + "\n"
    write(drop, definition.encode())
    try:
        restart(unit)
    except Exception:
        drop.unlink()
        restart(unit)
        path.unlink()
        raise


def rollback(unit, drop):
    if not drop.is_file():
        raise RuntimeError("No device-only drop-in to roll back")
    backup = drop.with_suffix(".disabled")
    drop.replace(backup)
    try:
        restart(unit)
    except Exception:
        backup.replace(drop)
        restart(unit)
        raise
    (ROOT / "device-only-remote.yml").unlink()


if __name__ == "__main__":
    os.umask(0o077)
    action, ident = sys.argv[1:]
    service, folder = NODES[ident]
    drop_in = Path("/etc/systemd/system", service + ".service.d", DROP_NAME)
    if action == "stop-test":
        run("systemctl", "stop", "simpleplane-device-only-acceptance")
        (ROOT / "device-only-candidate/remote.yml").unlink(missing_ok=True)
    elif action == "rollback":
        rollback(service, drop_in)
    elif action in {"test", "activate"}:
        command, index, shared_option, yaml = listener_command(service, folder)
        if action == "test":
            test(command, index, yaml)
        else:
            activate(service, drop_in, command, index, shared_option, yaml)
    else:
        raise ValueError("Unknown device-only operation")
    print(f"{action} complete for {ident}")
