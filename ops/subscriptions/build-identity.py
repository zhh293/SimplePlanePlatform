"""Build the single-host AuthSystemSimple adapter against its deployed Boot jar."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile


def build(source_root, original, output, javac):
    package = "com/authsystem/sso/config/"
    classname = "SigningJwkConfiguration"
    source = (source_root / "sso-server/src/main/java" / package / (classname + ".java")).read_text(encoding="utf-8")
    marker = "List<JWK> keys = production"
    if source.count(marker) != 1:
        raise ValueError("Review the upstream signing configuration before building for this version")
    source = source.replace(marker, marker + ' || environment.getProperty("sso.oidc-use-configured-keystore", Boolean.class, false)')
    if original.resolve() == output.resolve():
        raise ValueError("Specify a separate output jar")
    with tempfile.TemporaryDirectory() as scratch, zipfile.ZipFile(original) as app:
        tmp = Path(scratch)
        libs = tmp / "lib"
        libs.mkdir()
        for entry in app.infolist():
            if entry.filename.startswith("BOOT-INF/lib/") and entry.filename.endswith(".jar"):
                with app.open(entry) as src, (libs / Path(entry.filename).name).open("wb") as dst:
                    shutil.copyfileobj(src, dst)
        for dep in ["ProductionProfiles", "SsoProperties"]:
            path = tmp / package / (dep + ".class")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(app.read("BOOT-INF/classes/" + package + path.name))
        java = tmp / (classname + ".java")
        java.write_text(source, encoding="utf-8")
        subprocess.run([javac, "--release", "17", "-encoding", "UTF-8", "-proc:none", "-cp",
                        str(tmp) + os.pathsep + str(libs / "*"), "-d", str(tmp), str(java)], check=True)
        replacement = tmp / package / (classname + ".class")
        target = "BOOT-INF/classes/" + package + replacement.name
        app.getinfo(target)
        staging = output.with_suffix(".next")
        with zipfile.ZipFile(staging, "w") as dst:
            for entry in app.infolist():
                contents = replacement.read_bytes() if entry.filename == target else app.read(entry)
                dst.writestr(entry, contents)
        with zipfile.ZipFile(staging) as updated:
            assert updated.read(target) == replacement.read_bytes()
        staging.replace(output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source_root", type=Path)
    parser.add_argument("original", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--javac", default=shutil.which("javac"))
    args = parser.parse_args()
    build(args.source_root, args.original, args.output, args.javac)
