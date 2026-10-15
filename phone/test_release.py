#!/usr/bin/env python3
"""Focused checks for the portable BlackYT phone release ZIP."""
from __future__ import annotations

import hashlib
import io
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile
import tempfile
import zipfile

import build_phone
import package_release

ROOT = Path(__file__).resolve().parents[1]


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def parse_sums(data: bytes) -> dict[str, str]:
    result: dict[str, str] = {}
    for line in data.decode("ascii").splitlines():
        digest, separator, name = line.partition("  ")
        assert separator and re.fullmatch(r"[0-9a-f]{64}", digest), line
        assert name and name not in result, line
        result[name] = digest
    return result


def verify_html(html: bytes) -> None:
    source = html.decode("utf-8")
    lower = source.lower()
    assert "<style" in lower and "</style>" in lower
    assert not re.search(r"<link\b[^>]*\brel\s*=\s*['\"]?stylesheet", source, re.I)
    assert not re.search(r"<script\b[^>]*\bsrc\s*=", source, re.I)
    ids = set(re.findall(r"\bid=['\"]([^'\"]+)['\"]", source, re.I))
    targets = set(re.findall(r"\bdata-copy=['\"]([^'\"]+)['\"]", source, re.I))
    assert targets, "guide should expose copy buttons with targets"
    assert targets <= ids, f"missing copy targets: {sorted(targets - ids)}"


def run_setup_sandbox(zip_path: Path, altered: bool = False) -> tuple[subprocess.CompletedProcess[str], str, str]:
    with tempfile.TemporaryDirectory(prefix="blackyt release sandbox ") as tmp:
        root = Path(tmp) / "package directory with spaces"
        root.mkdir()
        with zipfile.ZipFile(zip_path) as archive:
            archive.extractall(root)
        installer_path = root / "install.sh"
        if altered:
            installer_path.write_bytes(installer_path.read_bytes() + b"\n# changed after packaging\n")

        mockbin = root / "mock commands"
        mockbin.mkdir()
        install_log = root / "installer.log"
        launch_log = root / "launch.log"
        mock_bash = mockbin / "bash"
        mock_bash.write_text(
            '#!/bin/sh\n'
            'printf "%s\\n" "$*" >> "$MOCK_INSTALL_LOG"\n'
            '[ "$1" = "./install.sh" ] || exit 41\n'
            '[ "$2" = "--local" ] || exit 42\n'
            '[ -f "$3" ] || exit 43\n'
            'exit 0\n'
        )
        mock_blackyt = mockbin / "blackyt"
        mock_blackyt.write_text('#!/bin/sh\nprintf launched >> "$MOCK_LAUNCH_LOG"\n')
        mock_bash.chmod(0o755)
        mock_blackyt.chmod(0o755)
        env = os.environ.copy()
        env.update(
            PATH=f"{mockbin}:{env.get('PATH', '')}",
            MOCK_INSTALL_LOG=str(install_log),
            MOCK_LAUNCH_LOG=str(launch_log),
        )
        result = subprocess.run(
            ["/bin/bash", str(root / "setup-phone.sh")],
            cwd=root, env=env, text=True, capture_output=True,
        )
        installed = install_log.read_text() if install_log.exists() else ""
        launched = launch_log.read_text() if launch_log.exists() else ""
        return result, installed, launched


def test_release_package() -> None:
    with tempfile.TemporaryDirectory(prefix="blackyt release build ") as tmp:
        base = Path(tmp)
        out_a = base / "first output"
        out_b = base / "second output"
        zip_a = package_release.package("v0.2.0", out_a)
        zip_b = package_release.package("v0.2.0", out_b)
        assert zip_a.read_bytes() == zip_b.read_bytes(), "same-environment ZIP builds must be deterministic"
        assert (out_a / "SHA256SUMS").read_bytes() == (out_b / "SHA256SUMS").read_bytes()

        with zipfile.ZipFile(zip_a) as archive:
            infos = archive.infolist()
            names = [info.filename for info in infos]
            assert len(names) == len(set(names)), "ZIP has duplicate members"
            bundle_names = [name for name in names if re.fullmatch(r"blackyt-phone-[0-9a-f]{16}\.tar\.gz", name)]
            assert len(bundle_names) == 1
            bundle_name = bundle_names[0]
            assert set(names) == {"START-HERE.html", "setup-phone.sh", "install.sh", bundle_name, "SHA256SUMS"}
            for info in infos:
                assert not info.is_dir() and not PurePosixPath(info.filename).is_absolute()
                assert ".." not in PurePosixPath(info.filename).parts
            content = {name: archive.read(name) for name in names}

        sums = parse_sums(content["SHA256SUMS"])
        assert set(sums) == set(content) - {"SHA256SUMS"}
        assert all(sums[name] == sha(data) for name, data in content.items() if name != "SHA256SUMS")
        external = parse_sums((out_a / "SHA256SUMS").read_bytes())
        assert external == {zip_a.name: sha(zip_a.read_bytes())}

        bundle = content[bundle_name]
        with tarfile.open(fileobj=io.BytesIO(bundle), mode="r:gz") as tar:
            members = tar.getmembers()
            names_in_tar = [member.name for member in members]
            assert len(names_in_tar) == len(set(names_in_tar))
            assert set(names_in_tar) == {"app/" + name for name in build_phone.RUNTIME}
            for member in members:
                path = PurePosixPath(member.name)
                assert not path.is_absolute() and ".." not in path.parts
                assert member.isfile() and not (member.issym() or member.islnk() or member.isdev() or member.isfifo())
        installer = content["install.sh"].decode("utf-8")
        pin = re.search(r"^BUNDLE_SHA256='([0-9a-f]{64})'$", installer, re.M)
        assert pin and pin.group(1) == sha(bundle)
        setup = content["setup-phone.sh"]
        syntax = subprocess.run(["/bin/bash", "-n"], input=setup, capture_output=True)
        assert syntax.returncode == 0, syntax.stderr.decode()
        guide = content["START-HERE.html"]
        verify_html(guide)
        assert (out_a / "BlackYT-Guide.html").read_bytes() == guide

        successful, installed, launched = run_setup_sandbox(zip_a)
        assert successful.returncode == 0, successful.stderr
        assert installed.startswith("./install.sh --local ") and "blackyt-phone-" in installed
        assert launched == "launched"

        rejected, altered_installer_calls, altered_launches = run_setup_sandbox(zip_a, altered=True)
        assert rejected.returncode != 0 and "checksum" in rejected.stderr.lower()
        assert altered_installer_calls == "" and altered_launches == "", "tampering must block before either mock launch"

    print("portable release checks passed: members/runtime whitelist, checksums and pin, shell syntax, mocked setup/tamper gate, paths with spaces, deterministic build, offline guide CSS/copy targets")


if __name__ == "__main__":
    test_release_package()
