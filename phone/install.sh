#!/usr/bin/env bash
set -eu

# Usage: bash install.sh https://host/path/blackyt-phone.tar.gz
#    or: bash install.sh --local /absolute/path/blackyt-phone.tar.gz
mode=url
BUNDLE_URL=''
BUNDLE_PATH=''
if [ "$#" -eq 1 ]; then
  BUNDLE_URL=$1
elif [ "$#" -eq 2 ] && [ "$1" = '--local' ]; then
  mode=local
  BUNDLE_PATH=$2
else
  printf 'Usage: bash install.sh https://host/path/bundle.tar.gz\n   or: bash install.sh --local /absolute/path/bundle.tar.gz\n' >&2
  exit 1
fi
BUNDLE_SHA256='__BUNDLE_SHA256__'
APP_HOME="$HOME/blackyt"
PREFIX_BIN="${PREFIX:-/data/data/com.termux/files/usr}/bin"

fail() { printf 'BlackYT installer: %s\n' "$*" >&2; exit 1; }
if [ "$mode" = url ]; then
  case "$BUNDLE_URL" in
    https://*|http://*) ;;
    *) fail 'bundle URL must use HTTP or HTTPS.';;
  esac
  case "$BUNDLE_URL" in http://*) printf 'Notice: this transfer uses HTTP; the pinned SHA-256 check authenticates the bundle bytes.\n';; esac
else
  case "$BUNDLE_PATH" in /*) ;; *) fail 'local bundle path must be absolute.';; esac
  [ -f "$BUNDLE_PATH" ] && [ ! -L "$BUNDLE_PATH" ] || fail 'local bundle must be an existing regular file, not a symlink.'
fi
case "$BUNDLE_SHA256" in
  *[!0-9a-fA-F]*) fail 'bundle SHA-256 was not set by the packager.';;
esac
[ "${#BUNDLE_SHA256}" -eq 64 ] || fail 'bundle SHA-256 must contain 64 hexadecimal characters.'

# Termux packages are installed without altering pip itself.
pkg install -y python nodejs-lts curl termux-tools

umask 077
work=$(mktemp -d "$HOME/.blackyt-install.XXXXXXXX") || fail 'could not create private staging directory.'
cleanup() { [ -n "${work-}" ] && [ -d "$work" ] && rm -rf -- "$work"; }
trap cleanup EXIT
trap 'exit 130' HUP INT TERM
archive="$work/bundle.tar.gz"
if [ "$mode" = local ]; then
  cp "$BUNDLE_PATH" "$archive" || fail 'could not copy the local bundle into private staging.'
else
  curl --fail --location --silent --show-error --retry 3 --retry-delay 2 --retry-connrefused --max-time 180 --output "$archive" "$BUNDLE_URL" || fail 'download failed after bounded retries.'
fi
actual=$(sha256sum "$archive" | cut -d ' ' -f 1)
[ "${actual}" = "$(printf '%s' "$BUNDLE_SHA256" | tr 'A-F' 'a-f')" ] || fail 'bundle SHA-256 does not match the pinned value.'

mkdir "$work/unpacked"
python - "$archive" "$work/unpacked" <<'PY'
import pathlib, sys, tarfile
archive, destination = sys.argv[1:]
root = pathlib.Path(destination).resolve()
with tarfile.open(archive, "r:gz") as bundle:
    members = bundle.getmembers()
    if not members:
        raise SystemExit("BlackYT installer: bundle is empty.")
    for member in members:
        path = pathlib.PurePosixPath(member.name)
        target = (root / pathlib.Path(*path.parts)).resolve()
        if path.is_absolute() or ".." in path.parts or not target.is_relative_to(root):
            raise SystemExit("BlackYT installer: unsafe path in bundle.")
        if member.issym() or member.islnk() or member.isdev() or member.isfifo():
            raise SystemExit("BlackYT installer: bundle contains a disallowed link or special file.")
    bundle.extractall(root, members=members)
PY
source_app="$work/unpacked/app"
[ -d "$source_app" ] && [ -f "$source_app/server.py" ] && [ -d "$source_app/public" ] && [ -f "$source_app/phone/blackyt.sh" ] || fail 'bundle is missing the expected app/ files.'

# Only manage a normal user-owned installation tree. Never follow a symlink at the install root.
if [ -e "$APP_HOME" ] || [ -L "$APP_HOME" ]; then
  [ -d "$APP_HOME" ] && [ ! -L "$APP_HOME" ] || fail "$APP_HOME exists but is not a normal directory; left untouched."
  [ ! -e "$APP_HOME/current" ] || [ -L "$APP_HOME/current" ] || fail 'existing current path is not a symlink; left untouched.'
  if [ -L "$APP_HOME/current" ]; then
    python - "$APP_HOME" <<'PYVALIDATE'
import os, sys
home = os.path.realpath(sys.argv[1])
current = os.path.join(sys.argv[1], "current")
target = os.path.realpath(current)
releases = os.path.realpath(os.path.join(home, "releases"))
if not os.path.islink(current) or os.path.commonpath((target, releases)) != releases or not os.path.isdir(target):
    raise SystemExit("BlackYT installer: current symlink is outside managed releases or broken; left untouched.")
PYVALIDATE
  fi
  if [ -e "$APP_HOME/.venv" ]; then
    [ -d "$APP_HOME/.venv" ] && [ ! -L "$APP_HOME/.venv" ] && [ -x "$APP_HOME/.venv/bin/python" ] || fail 'existing runtime is incomplete; left untouched.'
  fi
else
  mkdir -p "$APP_HOME"
fi
mkdir -p "$APP_HOME/releases" "$PREFIX_BIN"
release="$APP_HOME/releases/$(date +%Y%m%d%H%M%S)-$$"
[ ! -e "$release" ] || fail 'release path already exists; retry the installer.'
mkdir "$release"
cp -R "$source_app/." "$release/"

if [ ! -e "$APP_HOME/.venv" ]; then
  python -m venv "$APP_HOME/.venv"
fi
[ -d "$APP_HOME/.venv" ] && [ ! -L "$APP_HOME/.venv" ] && [ -x "$APP_HOME/.venv/bin/python" ] || fail 'runtime setup did not complete.'
"$APP_HOME/.venv/bin/python" -m pip install --no-cache-dir -r "$release/phone/requirements.txt"

# Install or update only a launcher that carries our ownership marker.
launcher="$PREFIX_BIN/blackyt"
if [ -e "$launcher" ] || [ -L "$launcher" ]; then
  [ -f "$launcher" ] && [ ! -L "$launcher" ] && grep -q '^# BLACKYT_TERMUX_LAUNCHER$' "$launcher" || fail "existing $launcher is not a managed BlackYT launcher; left untouched."
fi
cp "$release/phone/blackyt.sh" "$work/blackyt"
chmod 700 "$work/blackyt"
mv -f "$work/blackyt" "$launcher"

# Select the release atomically; os.replace replaces a symlink itself, never follows it.
python - "$APP_HOME" "$(basename "$release")" <<'PYSELECT'
import os, sys
home, name = sys.argv[1:]
next_link = os.path.join(home, ".current-next-" + str(os.getpid()))
current = os.path.join(home, "current")
try:
    os.symlink(os.path.join("releases", name), next_link)
    os.replace(next_link, current)
finally:
    if os.path.islink(next_link):
        os.unlink(next_link)
PYSELECT
printf 'Installed BlackYT in %s. Start it in the foreground with: blackyt\n' "$APP_HOME"
if [ "$mode" = local ]; then
  printf 'Local bundle SHA-256 was checked before extraction.\n'
else
  printf 'For the initial transfer, use the phone address and port shown by the bundle provider; SHA-256 is checked before extraction.\n'
fi
