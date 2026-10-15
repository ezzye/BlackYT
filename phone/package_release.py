#!/usr/bin/env python3
"""Create a portable, phone-only installer ZIP from whitelisted runtime files."""
import argparse
import hashlib
from pathlib import Path
import re
import tempfile
import zipfile
import build_phone

ROOT = Path(__file__).resolve().parents[1]

def package(version, output):
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ValueError("Use a version such as v0.2.0")
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="blackyt-release-") as temp:
        staging = Path(temp)
        metadata = build_phone.build("http://127.0.0.1:8765", staging)
        downloads = staging / "phone"
        installer = (downloads / metadata["installer"]).read_bytes()
        bundle = (downloads / metadata["bundle"]).read_bytes()
        guide = (ROOT / "docs/guide.html").read_text().replace("v0.2.0", version).encode()
        setup = ("#!/usr/bin/env bash\nset -eu\n"
                 "cd -- \"$(dirname -- \"$0\")\"\n"
                 "printf '%s  %s\\n' " + metadata["installerSha256"] + " install.sh | sha256sum -c -\n"
                 "bash ./install.sh --local \"$PWD/" + metadata["bundle"] + "\"\nblackyt\n").encode()
        files = {"START-HERE.html": guide, "setup-phone.sh": setup,
                 "install.sh": installer, metadata["bundle"]: bundle}
        checksums = "".join(hashlib.sha256(data).hexdigest() + "  " + name + "\n" for name, data in files.items())
        files["SHA256SUMS"] = checksums.encode()
        name = "BlackYT-Phone-" + version + ".zip"
        path = output / name
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
            for member, data in files.items():
                item = zipfile.ZipInfo(member, date_time=(2026, 10, 15, 0, 0, 0))
                item.compress_type = zipfile.ZIP_DEFLATED
                item.external_attr = (0o700 if member.endswith(".sh") else 0o600) << 16
                archive.writestr(item, data)
        (output / "SHA256SUMS").write_text(hashlib.sha256(path.read_bytes()).hexdigest() + "  " + name + "\n")
        (output / "BlackYT-Guide.html").write_bytes(guide)
        print("Portable release: " + str(path))
        return path

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    package(args.version, args.output)
