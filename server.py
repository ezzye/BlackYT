#!/usr/bin/env python3
"""BlackYT local metadata resolver. There is deliberately no media/proxy route."""
from __future__ import annotations
import argparse
import datetime as dt
import importlib.metadata
import json
import mimetypes
import os
from pathlib import Path
import re
import shutil
import socket
import ssl
import threading
import time
from collections import defaultdict, deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

ROOT = Path(__file__).resolve().parent
PUBLIC = ROOT / "public"
VIDEO_ID = re.compile(r"^[A-Za-z0-9_-]{11}$")
CACHE: dict[str, tuple[float, dict]] = {}
CACHE_LOCK = threading.Lock()
EXTRACT_SLOTS = threading.BoundedSemaphore(2)
REQUESTS: dict[str, deque] = defaultdict(deque)
REQUEST_LOCK = threading.Lock()


class ApiError(Exception):
    def __init__(self, code, message, status=400, detail=None):
        self.code, self.message, self.status, self.detail = code, message, status, detail
        super().__init__(message)


def filters(body):
    mode = body.get("mode", "music")
    if mode not in ("music", "interests"):
        raise ApiError("INPUT", "Choose Music or Interests.")
    result = {"mode": mode}
    for key in ("include", "exclude"):
        values = body.get(key, [])
        if not isinstance(values, list) or len(values) > 12 or any(
            not isinstance(v, str) or len(v) > 80 for v in values
        ):
            raise ApiError("INPUT", "Use up to 12 short include/exclude phrases.")
        result[key] = [v.strip().casefold() for v in values if v.strip()]
    return result


def text_matches(info, rules):
    # Intentionally use the same fields in search and resolution. Descriptions
    # are excluded so an incidental phrase in a long description cannot admit a track.
    haystack = " ".join(str(info.get(k) or "") for k in ("title", "channel", "uploader")).casefold()
    if any(term in haystack for term in rules["exclude"]):
        return False
    return not rules["include"] or any(term in haystack for term in rules["include"])


def music_category(info):
    return any(str(c).casefold() == "music" for c in info.get("categories") or [])


def direct_url(raw):
    if not isinstance(raw, str):
        raise ApiError("HOST", "No direct YouTube media URL was returned.", 502)
    try:
        parsed = urlparse(raw)
        valid = (parsed.scheme == "https" and parsed.hostname
                 and parsed.hostname.lower().endswith(".googlevideo.com")
                 and not parsed.username and not parsed.password
                 and parsed.port in (None, 443))
    except ValueError:
        valid = False
    if not valid:
        raise ApiError("HOST", "The resolver returned a non-YouTube media host; playback was blocked.", 502)
    expiry = parse_qs(parsed.query).get("expire", [None])[0]
    try:
        expiry = int(expiry) if expiry else None
    except ValueError:
        expiry = None
    if expiry and expiry < time.time() + 45:
        raise ApiError("EXPIRED", "The direct stream URL is already expiring. Try Play again.", 502)
    return parsed.hostname, expiry


class QuietLogger:
    # Never log signed media URLs, account details, or search contents.
    def debug(self, _): pass
    def warning(self, _): pass
    def error(self, _): pass


def extract(target, *, flat=False):
    try:
        import yt_dlp
    except ImportError:
        raise ApiError("SETUP", "The local resolver is not installed.", 503,
                       "Run setup.sh in the project folder, then start.sh.")
    if not EXTRACT_SLOTS.acquire(timeout=1):
        raise ApiError("RATE_LIMITED", "The resolver is busy. Retry in a moment.", 429)
    try:
        opts = {"quiet": True, "no_warnings": True, "logger": QuietLogger(),
                "skip_download": True, "noplaylist": not flat, "extract_flat": "in_playlist" if flat else False,
                "socket_timeout": 15, "retries": 0, "extractor_retries": 0,
                "cachedir": False, "ignoreerrors": False, "noprogress": True,
                "format": "bestaudio[protocol=https][ext=m4a]/bestaudio[protocol=https]"}
        deno = shutil.which("deno")
        node = shutil.which("node")
        if deno:
            opts["js_runtimes"] = {"deno": {"path": deno}}
        elif node:
            opts["js_runtimes"] = {"node": {"path": node}}
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(target, download=False)
        if not info:
            raise ApiError("NOT_FOUND", "YouTube returned no usable metadata.", 502)
        return info
    except ApiError:
        raise
    except Exception as error:
        message = str(error).lower()
        if "429" in message or "too many requests" in message:
            raise ApiError("RATE_LIMITED", "YouTube is limiting requests. Wait before retrying.", 429)
        if "sign in" in message or "bot" in message or "403" in message:
            raise ApiError("YOUTUBE_ACCESS", "YouTube refused access from this connection.", 502,
                           "This proof of concept does not use account cookies or route media through a proxy.")
        if "format" in message:
            raise ApiError("NOT_FOUND", "YouTube did not provide a supported direct audio format.", 502,
                           "Try another public video or update the local resolver. No relay fallback is used.")
        raise ApiError("YOUTUBE_ACCESS", "The public YouTube request could not complete.", 502,
                       "Check the connection and resolver version, then retry.")
    finally:
        EXTRACT_SLOTS.release()


def cache_get(key):
    with CACHE_LOCK:
        entry = CACHE.get(key)
        if entry and entry[0] > time.time():
            return entry[1]
        CACHE.pop(key, None)


def cache_put(key, value, seconds):
    with CACHE_LOCK:
        if len(CACHE) >= 100:
            CACHE.pop(next(iter(CACHE)))
        CACHE[key] = (time.time() + seconds, value)


def thumbnail(video_id):
    return f"https://i.ytimg.com/vi/{video_id}/mqdefault.jpg"


def duration_text(seconds):
    try:
        seconds = int(seconds)
        return f"{seconds // 60}:{seconds % 60:02d}"
    except (TypeError, ValueError):
        return ""


def search(body):
    rules = filters(body)
    query = body.get("query", "")
    if not isinstance(query, str) or not 1 <= len(query.strip()) <= 180:
        raise ApiError("INPUT", "Enter a search or a public YouTube video link (up to 180 characters).")
    query = query.strip()
    # A link is normalized to a video ID; arbitrary fetch URLs never reach the extractor.
    video_id = None
    if VIDEO_ID.fullmatch(query):
        video_id = query
    elif query.startswith(("http://", "https://")):
        parsed = urlparse(query)
        if parsed.hostname in ("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com"):
            video_id = parse_qs(parsed.query).get("v", [None])[0]
            if parsed.path.startswith(("/shorts/", "/live/")):
                video_id = parsed.path.split("/")[2]
        elif parsed.hostname == "youtu.be":
            video_id = parsed.path.lstrip("/").split("/")[0]
        if not video_id or not VIDEO_ID.fullmatch(video_id):
            raise ApiError("INPUT", "Use a public YouTube video link, video ID, or plain search words.")
    if video_id:
        info = cache_get("video:" + video_id)
        if info is None:
            info = extract("https://www.youtube.com/watch?v=" + video_id)
            cache_put("video:" + video_id, info, 120)
        entries = [info]
    else:
        search_words = query + (" music" if rules["mode"] == "music" and "music" not in query.casefold() else "")
        key = "search:" + search_words
        data = cache_get(key)
        if data is None:
            data = extract("ytsearch8:" + search_words, flat=True)
            cache_put(key, data, 120)
        entries = data.get("entries") or []
    items = []
    for entry in entries:
        if not entry or not VIDEO_ID.fullmatch(str(entry.get("id", ""))) or not text_matches(entry, rules):
            continue
        if rules["mode"] == "music" and entry.get("categories") is not None and not music_category(entry):
            continue
        vid = entry["id"]
        items.append({"id": vid, "title": entry.get("title") or "Untitled", "channel": entry.get("channel") or entry.get("uploader") or "Unknown channel",
                      "duration": duration_text(entry.get("duration")), "thumbnail": thumbnail(vid),
                      "url": "https://www.youtube.com/watch?v=" + vid,
                      "matchBasis": "Music category checked on Play" if rules["mode"] == "music" else "Title/channel interest rules"})
    return {"items": items, "notice": f"{len(items)} results. " + ("Music search is a candidate list; Play verifies YouTube's Music category. " if rules["mode"] == "music" else "") + "Include matches any phrase; exclude wins. Rules inspect title and channel, not the audio."}


def resolve(body):
    rules = filters(body)
    video_id = body.get("id")
    if not isinstance(video_id, str) or not VIDEO_ID.fullmatch(video_id):
        raise ApiError("INPUT", "A valid YouTube video ID is required.")
    info = cache_get("video:" + video_id)
    if info is None:
        info = extract("https://www.youtube.com/watch?v=" + video_id)
        cache_put("video:" + video_id, info, 120)
    if not text_matches(info, rules):
        raise ApiError("FILTER", "This item no longer matches your include/exclude rules.", 422)
    if rules["mode"] == "music" and not music_category(info):
        raise ApiError("CATEGORY", "This video is not labelled Music by YouTube.", 422,
                       "Choose another result or switch to Interests.")
    if info.get("is_live") or info.get("live_status") == "is_live":
        raise ApiError("UNSUPPORTED", "Live broadcasts are outside this first direct-audio test.", 422)
    try:
        host, expiry = direct_url(info.get("url"))
    except ApiError as error:
        if error.code != "EXPIRED":
            raise
        # Cached metadata can outlive a short-lived media signature. Refresh
        # once instead of giving every subsequent Play the same expired URL.
        with CACHE_LOCK:
            CACHE.pop("video:" + video_id, None)
        info = extract("https://www.youtube.com/watch?v=" + video_id)
        if not text_matches(info, rules):
            raise ApiError("FILTER", "This item no longer matches your filters.", 422)
        if rules["mode"] == "music" and not music_category(info):
            raise ApiError("CATEGORY", "This video is not labelled Music by YouTube.", 422)
        host, expiry = direct_url(info.get("url"))
        cache_put("video:" + video_id, info, 120)
    ext = info.get("ext")
    if info.get("vcodec") != "none" or ext not in ("m4a", "webm"):
        raise ApiError("UNSUPPORTED", "No supported audio-only HTTPS stream was returned.", 502)
    return {"id": video_id, "title": info.get("title"), "channel": info.get("channel") or info.get("uploader"),
            "url": info["url"], "mime": "audio/mp4" if ext == "m4a" else "audio/webm",
            "host": host, "expiresAt": dt.datetime.fromtimestamp(expiry, dt.timezone.utc).isoformat() if expiry else None,
            "duration": info.get("duration"), "sourceUrl": "https://www.youtube.com/watch?v=" + video_id,
            "categories": info.get("categories") or [], "matchBasis": "YouTube Music category + title/channel rules" if rules["mode"] == "music" else "Title/channel rules",
            "directOnly": True}


class Handler(BaseHTTPRequestHandler):
    server_version = "BlackYT/0.1"

    def log_message(self, fmt, *args):
        # Log method/status only, never URLs, signed tokens, or searches.
        if len(args) >= 2:
            print(f"{self.command} {args[1]}", flush=True)

    def valid_host(self):
        try:
            host = urlparse("http://" + self.headers.get("Host", "")).hostname
        except ValueError:
            host = None
        return host in self.server.allowed_hosts

    def reply(self, status, body, content_type="application/json; charset=utf-8", cache=False, download=None):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        if download:
            self.send_header("Content-Disposition", f'attachment; filename="{download}"')
        self.send_header("Cache-Control", "public, max-age=0, must-revalidate" if cache else "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        script = "'self' 'unsafe-inline'" if self.path.split("?")[0] in ("/report.html", "/certificate.html") else "'self'"
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src " + script + "; style-src 'self' 'unsafe-inline'; img-src 'self' https://i.ytimg.com data:; media-src https://*.googlevideo.com; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; worker-src 'self'; manifest-src 'self'")
        self.end_headers()
        self.wfile.write(body)

    def json(self, status, data):
        self.reply(status, json.dumps(data, ensure_ascii=False).encode())

    def failure(self, error):
        data = {"error": error.message, "code": error.code}
        if error.detail:
            data["detail"] = error.detail
        self.json(error.status, data)

    def do_GET(self):
        if not self.valid_host():
            self.json(403, {"error": "Host not permitted.", "code": "HOST"})
            return
        path = urlparse(self.path).path
        if path in ("/download-certificate", "/blackyt-local-ca.cer"):
            certificate = PUBLIC / "blackyt-local-ca.cer"
            if not certificate.is_file():
                self.json(404, {"error": "Local certificate is not prepared.", "code": "NOT_FOUND"})
                return
            self.reply(200, certificate.read_bytes(), "application/octet-stream", download="blackyt-local-ca.cer")
            return
        if path == "/api/status":
            try:
                version = importlib.metadata.version("yt-dlp")
                ready = True
            except importlib.metadata.PackageNotFoundError:
                version, ready = None, False
            self.json(200, {"ok": ready, "resolver": "yt-dlp metadata only", "version": version,
                            "directOnly": True, "mediaRelay": False, "screenOffTest": "pending"})
            return
        if path.startswith("/api/"):
            self.json(404, {"error": "No such route. Media relay is not provided.", "code": "NOT_FOUND"})
            return
        if path == "/blackyt-poc.zip" and (ROOT / "blackyt-poc.zip").is_file():
            self.reply(200, (ROOT / "blackyt-poc.zip").read_bytes(), "application/zip")
            return
        file = (PUBLIC / (unquote(path).lstrip("/") or "index.html")).resolve()
        if not file.is_relative_to(PUBLIC.resolve()) or not file.is_file():
            self.json(404, {"error": "File not found.", "code": "NOT_FOUND"})
            return
        mime = {".js": "text/javascript", ".webmanifest": "application/manifest+json"}.get(file.suffix) or mimetypes.guess_type(file.name)[0] or "application/octet-stream"
        self.reply(200, file.read_bytes(), mime, cache=True)

    def do_POST(self):
        try:
            if not self.valid_host():
                raise ApiError("HOST", "Host not permitted.", 403)
            origin = self.headers.get("Origin")
            if origin and urlparse(origin).netloc != self.headers.get("Host"):
                raise ApiError("ORIGIN", "Use this app's own page to make requests.", 403)
            if self.headers.get("Sec-Fetch-Site") == "cross-site":
                raise ApiError("ORIGIN", "Cross-site requests are not accepted.", 403)
            with REQUEST_LOCK:
                history = REQUESTS[self.client_address[0]]
                while history and history[0] < time.time() - 60:
                    history.popleft()
                if len(history) >= 30:
                    raise ApiError("RATE_LIMITED", "Too many requests. Wait a minute.", 429)
                history.append(time.time())
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 8192 or self.headers.get("Transfer-Encoding"):
                raise ApiError("INPUT", "Request body is missing or too large.", 413)
            if self.headers.get_content_type() != "application/json":
                raise ApiError("INPUT", "JSON content is required.", 415)
            body = json.loads(self.rfile.read(length))
            if not isinstance(body, dict):
                raise ApiError("INPUT", "Expected a JSON object.")
            route = urlparse(self.path).path
            if route == "/api/search":
                result = search(body)
            elif route == "/api/resolve":
                result = resolve(body)
            else:
                raise ApiError("NOT_FOUND", "No such route. Media relay is not provided.", 404)
            self.json(200, result)
        except ApiError as error:
            self.failure(error)
        except (ValueError, UnicodeDecodeError):
            self.failure(ApiError("INPUT", "Malformed JSON or request length."))
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception:
            self.failure(ApiError("INTERNAL", "The local resolver could not complete this request.", 500))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--listen", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--allow-host", action="append", default=[])
    parser.add_argument("--cert", type=Path)
    parser.add_argument("--key", type=Path)
    args = parser.parse_args()
    if bool(args.cert) != bool(args.key):
        parser.error("--cert and --key must be supplied together")
    server = ThreadingHTTPServer((args.listen, args.port), Handler)
    server.allowed_hosts = {"localhost", "127.0.0.1", "::1", args.listen, *args.allow_host}
    if args.cert:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(args.cert, args.key)
        server.socket = context.wrap_socket(server.socket, server_side=True)
    scheme = "https" if args.cert else "http"
    print(f"BlackYT: {scheme}://{args.listen}:{args.port} — metadata only; no media relay", flush=True)
    print("Use the same public network exit for resolver and player; direct URLs may be IP-bound.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        server.server_close()


if __name__ == "__main__":
    main()
