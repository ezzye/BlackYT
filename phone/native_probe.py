#!/usr/bin/env python3
"""Bounded native HTTPS range probe for two configured YouTube videos."""
import importlib.util
import json
from pathlib import Path
import ssl
import sys
import urllib.error
import urllib.request

TARGETS = {
    "Prince UG3VcCAlUgE": "https://www.youtube.com/watch?v=UG3VcCAlUgE",
    "controlCarefree sMcWOaJFuw0": "https://www.youtube.com/watch?v=sMcWOaJFuw0",
}
LIMIT = 4096


def load_server():
    path = Path.home() / "blackyt/current/server.py"
    spec = importlib.util.spec_from_file_location("blackyt_probe_server", path)
    if not spec or not spec.loader:
        raise RuntimeError("Could not load existing BlackYT server module")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def one_probe(server, label, watch_url):
    info = server.extract(watch_url)
    url = info.get("url")
    host, _ = server.direct_url(url)
    ctx = ssl.create_default_context()
    base_headers = {"Range": f"bytes=0-{LIMIT - 1}"}
    used = "default"
    try:
        response = urllib.request.urlopen(urllib.request.Request(url, headers=base_headers), timeout=15, context=ctx)
    except urllib.error.HTTPError as first:
        response = first
        if first.code == 403:
            ua = next((v for k, v in (info.get("http_headers") or {}).items() if k.lower() == "user-agent"), None)
            if ua:
                try:
                    first.close()
                    response = urllib.request.urlopen(urllib.request.Request(url, headers={**base_headers, "User-Agent": ua}), timeout=15, context=ctx)
                    used = "extractor"
                except urllib.error.HTTPError as retry:
                    response = retry
                except Exception:
                    pass
    except Exception:
        return {"id": label, "status": None, "mime": "", "bytes": 0, "host": host, "headers": "default", "error": "network_error"}
    try:
        body = response.read(LIMIT)
        return {"id": label, "status": response.status, "mime": response.headers.get_content_type(),
                "bytes": len(body), "host": host, "headers": used,
                "error": "http_error" if response.status >= 400 else ""}
    finally:
        response.close()


def main():
    checks = []
    try:
        server = load_server()
        for label, url in TARGETS.items():
            try:
                checks.append(one_probe(server, label, url))
            except Exception:
                checks.append({"id": label, "status": None, "mime": "", "bytes": 0,
                               "host": "", "headers": "default", "error": "probe_error"})
    except Exception:
        checks = [{"id": label, "status": None, "mime": "", "bytes": 0,
                   "host": "", "headers": "default", "error": "setup_error"} for label in TARGETS]
    report = {"checks": checks, "runtime": sys.version.split()[0]}
    destination = Path.home() / "blackyt/native-probe-result.json"
    try:
        destination.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    except OSError:
        pass
    for item in checks:
        print(f'{item["id"]}: status={item["status"] or "error"} mime={item["mime"] or "unknown"} bytes={item["bytes"]}')
    print(f"Safe evidence: {destination}")
    try:
        req = urllib.request.Request("http://127.0.0.1:8767/native-probe-result",
            data=json.dumps(report).encode("utf-8"), headers={"Content-Type": "application/json"}, method="POST")
        urllib.request.urlopen(req, timeout=3).close()
        print("Local report forwarded to Mac.")
    except Exception:
        print("Local forwarding unavailable; report remains on phone.")

if __name__ == "__main__":
    main()
