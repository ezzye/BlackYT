#!/data/data/com.termux/files/usr/bin/sh
# BLACKYT_TERMUX_LAUNCHER
set -eu
PREFIX_BIN="${PREFIX:-/data/data/com.termux/files/usr}/bin"
APP_HOME="$HOME/blackyt"
BASE_URL='http://127.0.0.1:8765'
PYTHON="$APP_HOME/.venv/bin/python"
server_pid=''

fail() { printf 'BlackYT: %s\n' "$*" >&2; exit 1; }
compatible_status() {
  status_json=$(curl --silent --show-error --fail --max-time 2 "$BASE_URL/api/status" 2>/dev/null) || return 1
  printf '%s' "$status_json" | "$PYTHON" -c 'import json,sys; d=json.load(sys.stdin); sys.exit(0 if d.get("product")=="BlackYT" and d.get("runtime")=="android-local" and d.get("ok") is True and d.get("resolver")=="yt-dlp metadata only" and (d.get("directOnly") is True and d.get("mediaRelay") is False or d.get("youtubeToDeviceOnly") is True and d.get("externalMediaRelay") is False and d.get("relayLocation")=="this-device") else 1)' 2>/dev/null || return 1
  printf '%s\n' "$status_json"
}
show_status() {
  result=$(compatible_status) || return 1
  version=$(printf '%s' "$result" | "$PYTHON" -c 'import json,sys; print(json.load(sys.stdin).get("version") or "unknown")')
  printf 'BlackYT is ready at %s (yt-dlp %s).\n' "$BASE_URL" "$version"
}
open_page() {
  if [ -x "$PREFIX_BIN/termux-open-url" ]; then
    "$PREFIX_BIN/termux-open-url" "$BASE_URL/" || return 1
    return 0
  fi
  return 1
}
unlock() { "$PREFIX_BIN/termux-wake-unlock" >/dev/null 2>&1 || :; }
cleanup() {
  if [ -n "$server_pid" ]; then
    kill "$server_pid" 2>/dev/null || :
    wait "$server_pid" 2>/dev/null || :
    server_pid=''
  fi
  unlock
}
on_signal() { cleanup; exit 130; }

command=${1-start}
case "$command" in
  status)
    shift
    [ "$#" -eq 0 ] || fail 'usage: blackyt status'
    show_status || fail 'no ready BlackYT server is responding on 127.0.0.1:8765.'
    ;;
  open)
    shift
    [ "$#" -eq 0 ] || fail 'usage: blackyt open'
    show_status || fail 'BlackYT is not ready. Run: blackyt start'
    if ! open_page; then printf 'Open this address in your browser: %s/\n' "$BASE_URL" >&2; fi
    ;;
  start)
    [ "$#" -eq 0 ] || shift
    [ "$#" -eq 0 ] || fail 'usage: blackyt [start]'
    [ -x "$PYTHON" ] && [ -f "$APP_HOME/current/server.py" ] || fail 'installation is incomplete; rerun install.sh.'
    if show_status; then
      printf 'The compatible BlackYT server is already running.\n'
      if ! open_page; then printf 'Open this address in your browser: %s/\n' "$BASE_URL" >&2; fi
      exit 0
    fi
    # A non-BlackYT service may occupy the port. Never signal or stop its process.
    if ! "$PYTHON" -c 'import socket; s=socket.socket(); s.settimeout(1); s.bind(("127.0.0.1",8765)); s.close()' 2>/dev/null; then
      fail '127.0.0.1:8765 is occupied by a service that did not identify as BlackYT; it was left running.'
    fi
    [ -x "$PREFIX_BIN/termux-wake-lock" ] || fail 'termux-wake-lock is unavailable; install package termux-tools.'
    "$PREFIX_BIN/termux-wake-lock" || fail 'could not acquire the Termux wakelock.'
    trap cleanup EXIT
    trap on_signal HUP INT TERM
    cd "$APP_HOME/current"
    "$PYTHON" server.py --phone --listen 127.0.0.1 --port 8765 &
    server_pid=$!
    ready=0
    attempt=0
    while [ "$attempt" -lt 20 ]; do
      if show_status; then ready=1; break; fi
      if ! kill -0 "$server_pid" 2>/dev/null; then wait "$server_pid" || :; server_pid=''; fail 'BlackYT server stopped before becoming ready.'; fi
      attempt=$((attempt + 1))
      sleep 1
    done
    [ "$ready" -eq 1 ] || fail 'BlackYT did not become ready within 20 seconds.'
    if ! open_page; then printf 'Could not open the browser. Open this address manually: %s/\n' "$BASE_URL" >&2; fi
    wait "$server_pid"
    server_pid=''
    ;;
  *) fail 'usage: blackyt [start|open|status]' ;;
esac
