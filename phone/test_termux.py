"""Bounded mocked checks for the staged Termux installer and launcher."""
from __future__ import annotations

import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tarfile
import tempfile
import time

HERE = Path(__file__).resolve().parent
RESULT = {"checks": {}, "limitations": ["No physical Android or Termux device was used."]}


def run_install_checks(root: Path) -> None:
    home = root / "install-home"
    home.mkdir()
    prefix = root / "install-prefix"
    (prefix / "bin").mkdir(parents=True)
    mockbin = root / "mockbin"
    mockbin.mkdir()
    app = root / "app"
    (app / "public").mkdir(parents=True)
    (app / "phone").mkdir()
    (app / "server.py").write_text("# mocked app\n")
    (app / "public" / "index.html").write_text("ok")
    shutil.copy(HERE / "blackyt.sh", app / "phone" / "blackyt.sh")
    shutil.copy(HERE / "requirements.txt", app / "phone" / "requirements.txt")
    bundle = root / "bundle.tar.gz"
    with tarfile.open(bundle, "w:gz") as archive:
        archive.add(app, arcname="app")

    installer = (HERE / "install.sh").read_text().replace(
        "__BUNDLE_SHA256__", hashlib.sha256(bundle.read_bytes()).hexdigest()
    )
    installer_path = root / "install.sh"
    installer_path.write_text(installer)
    (mockbin / "pkg").write_text('#!/bin/sh\necho "$*" >> "$MOCK_LOG"\n')
    (mockbin / "curl").write_text(
        '#!/bin/sh\nwhile [ "$#" -gt 0 ]; do case "$1" in '
        '--output) out=$2; shift 2;; *) shift;; esac; done\n'
        'cp "$MOCK_BUNDLE" "$out"\n'
    )
    (mockbin / "python").write_text(
        '''#!/bin/sh
if [ "$1" = "-m" ] && [ "$2" = "venv" ]; then
 mkdir -p "$3/bin"
 cat > "$3/bin/python" <<'PYMOCK'
#!/bin/sh
if [ "$1" = "-m" ] && [ "$2" = "pip" ]; then echo pip-install >> "$MOCK_LOG"; exit 0; fi
if [ "$1" = "-c" ]; then exit 0; fi
exit 0
PYMOCK
 chmod +x "$3/bin/python"; exit 0
fi
exec python3 "$@"
'''
    )
    for path in mockbin.iterdir():
        path.chmod(0o755)
    env = os.environ.copy()
    env.update(
        HOME=str(home), PREFIX=str(prefix),
        PATH=f"{mockbin}:{os.environ['PATH']}",
        MOCK_LOG=str(root / "install.log"), MOCK_BUNDLE=str(bundle),
    )
    command = ["bash", str(installer_path), "http://192.168.1.228:8765/blackyt.tar.gz"]
    first = subprocess.run(command, env=env, text=True, capture_output=True)
    assert first.returncode == 0, first.stderr
    first_release = os.readlink(home / "blackyt" / "current")
    runtime = home / "blackyt" / ".venv" / "bin" / "python"
    second = subprocess.run(command, env=env, text=True, capture_output=True)
    assert second.returncode == 0, second.stderr
    second_release = os.readlink(home / "blackyt" / "current")
    log = (root / "install.log").read_text()
    assert first_release.startswith("releases/") and second_release.startswith("releases/")
    assert first_release != second_release and runtime.exists()
    assert log.count("pip-install") == 2 and "termux-tools" in log
    assert "HTTP" in second.stdout and "Start it in the foreground" in second.stdout
    RESULT["checks"]["mock_install_and_rerun"] = "passed"
    original = bundle.read_bytes()
    bundle.write_bytes(b"tampered download")
    tampered = subprocess.run(command, env=env, text=True, capture_output=True)
    assert tampered.returncode != 0 and "SHA-256 does not match" in tampered.stderr
    assert os.readlink(home / "blackyt" / "current") == second_release
    with tarfile.open(bundle, "w:gz") as archive:
        member = tarfile.TarInfo("../escape"); member.size = 1
        archive.addfile(member, io.BytesIO(b"x"))
    installer_path.write_text((HERE / "install.sh").read_text().replace("__BUNDLE_SHA256__", hashlib.sha256(bundle.read_bytes()).hexdigest()))
    traversal = subprocess.run(command, env=env, text=True, capture_output=True)
    assert traversal.returncode != 0 and "unsafe path" in traversal.stderr
    assert os.readlink(home / "blackyt" / "current") == second_release
    assert not (root / "escape").exists()
    RESULT["checks"]["tampered_bytes_and_traversal_rejected"] = "passed"



def run_launcher_checks(root: Path) -> None:
    home = root / "launch-home"
    prefix = root / "launch-prefix"
    (prefix / "bin").mkdir(parents=True)
    mockbin = root / "launch-bin"
    mockbin.mkdir()
    current = home / "blackyt" / "current"
    current.mkdir(parents=True)
    (current / "server.py").touch()
    venv_bin = home / "blackyt" / ".venv" / "bin"
    venv_bin.mkdir(parents=True)
    fake_python = venv_bin / "python"
    fake_python.write_text(
        '''#!/bin/sh
if [ "$1" = "-c" ]; then case "$*" in *"import socket"*) exit 0;; esac; exec python3 "$@"; fi
if [ "$1" = "server.py" ]; then echo server-start >> "$MOCK_LOG"; while :; do sleep 1; done; fi
'''
    )
    fake_python.chmod(0o755)
    (mockbin / "curl").write_text(
        '''#!/bin/sh
[ "${MOCK_READY:-}" = 1 ] || grep -q server-start "$MOCK_LOG" || exit 7
printf '{"product":"BlackYT","runtime":"android-local","ok":true,"resolver":"yt-dlp metadata only","directOnly":true,"mediaRelay":false,"version":"2026.8.19"}'
'''
    )
    (mockbin / "curl").chmod(0o755)
    for name in ("termux-wake-lock", "termux-wake-unlock", "termux-open-url"):
        tool = prefix / "bin" / name
        tool.write_text(f'#!/bin/sh\necho "{name} $*" >> "$MOCK_LOG"\n')
        tool.chmod(0o755)
    env = os.environ.copy()
    env.update(
        HOME=str(home), PREFIX=str(prefix),
        PATH=f"{mockbin}:{os.environ['PATH']}", MOCK_LOG=str(root / "launch.log"),
    )
    launcher = HERE / "blackyt.sh"
    status_env = env.copy()
    status_env["MOCK_READY"] = "1"
    status = subprocess.run(["sh", str(launcher), "status"], env=status_env, text=True, capture_output=True)
    assert status.returncode == 0 and "yt-dlp 2026.8.19" in status.stdout, status.stderr
    process = subprocess.Popen(["sh", str(launcher)], env=env, text=True,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    time.sleep(1.5)
    process.send_signal(signal.SIGTERM)
    stdout, stderr = process.communicate(timeout=5)
    log = (root / "launch.log").read_text()
    assert "server-start" in log and "termux-open-url http://127.0.0.1:8765/" in log
    assert "termux-wake-unlock" in log and log.index("termux-open-url") > log.index("server-start")
    RESULT["checks"]["mock_launcher_status_and_foreground_start"] = "passed"

    foreign_home = root / "foreign-home"
    foreign_prefix = root / "foreign-prefix"
    (foreign_home / "blackyt" / "current").mkdir(parents=True)
    (foreign_home / "blackyt" / "current" / "server.py").touch()
    foreign_python = foreign_home / "blackyt" / ".venv" / "bin"
    foreign_python.mkdir(parents=True)
    fake = foreign_python / "python"
    fake.write_text('#!/bin/sh\ncase "$*" in *"import socket"*) exit 1;; esac\nexec python3 "$@"\n')
    fake.chmod(0o755)
    (mockbin / "curl").write_text('#!/bin/sh\nprintf \'{"product":"other"}\'\n')
    env.update(HOME=str(foreign_home), PREFIX=str(foreign_prefix))
    collision = subprocess.run(["sh", str(launcher)], env=env, text=True, capture_output=True)
    assert collision.returncode != 0 and "occupied by a service" in collision.stderr
    RESULT["checks"]["foreign_service_collision"] = "passed"


def main() -> None:
    syntax_shells = {
        "install_bash_n": subprocess.run(["bash", "-n", str(HERE / "install.sh")]).returncode,
        "launcher_sh_n": subprocess.run(["sh", "-n", str(HERE / "blackyt.sh")]).returncode,
    }
    assert all(code == 0 for code in syntax_shells.values()), syntax_shells
    RESULT["checks"]["shell_syntax"] = "passed"
    RESULT["environment"] = syntax_shells
    with tempfile.TemporaryDirectory(prefix="blackyt-test-") as temp:
        root = Path(temp)
        run_install_checks(root)
        run_launcher_checks(root)
    RESULT["overall"] = "passed"
    (HERE / "test-results.json").write_text(json.dumps(RESULT, indent=2) + "\n")
    print(json.dumps(RESULT, indent=2))


if __name__ == "__main__":
    main()
