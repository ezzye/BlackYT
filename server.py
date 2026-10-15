#!/usr/bin/env python3
"""BlackYT local resolver with an optional phone-local audio stream."""
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
import secrets
import urllib.error
import urllib.request
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
AUDIO_TOKENS: dict[str, tuple[float, str, str, str]] = {}
AUDIO_TOKEN_LOCK = threading.Lock()
AUDIO_STREAMS = threading.BoundedSemaphore(4)
MAX_AUDIO_TOKENS = 40
MAX_AUDIO_TOKEN_TTL = 6 * 60 * 60
STREAM_TIMEOUT = 15


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
        raise ApiError("HOST", "No direct YT media URL was returned.", 502)
    try:
        parsed = urlparse(raw)
        valid = (parsed.scheme == "https" and parsed.hostname
                 and parsed.hostname.lower().endswith(".googlevideo.com")
                 and not parsed.username and not parsed.password
                 and parsed.port in (None, 443))
    except ValueError:
        valid = False
    if not valid:
        raise ApiError("HOST", "The resolver returned a non-YT media host; playback was blocked.", 502)
    expiry = parse_qs(parsed.query).get("expire", [None])[0]
    try:
        expiry = int(expiry) if expiry else None
    except ValueError:
        expiry = None
    if expiry and expiry < time.time() + 45:
        raise ApiError("EXPIRED", "The direct stream URL is already expiring. Try Play again.", 502)
    return parsed.hostname, expiry


def phone_audio_result(result):
    """Replace a signed URL with an opaque, short-lived local route."""
    now = time.time()
    host, expiry = direct_url(result.get("url"))
    token_expiry = min(now + MAX_AUDIO_TOKEN_TTL, float(expiry or now + MAX_AUDIO_TOKEN_TTL))
    token = secrets.token_urlsafe(24)
    with AUDIO_TOKEN_LOCK:
        for old, value in list(AUDIO_TOKENS.items()):
            if value[0] <= now:
                AUDIO_TOKENS.pop(old, None)
        while len(AUDIO_TOKENS) >= MAX_AUDIO_TOKENS:
            AUDIO_TOKENS.pop(next(iter(AUDIO_TOKENS)))
        AUDIO_TOKENS[token] = (token_expiry, result["url"], result["mime"], result["id"])
    return {key: value for key, value in result.items() if key != "url"} | {
        "url": "/api/audio/" + token, "sourceHost": host, "transport": "phone-local",
        "directOnly": False, "youtubeToDeviceOnly": True, "externalMediaRelay": False,
    }


def audio_token(token):
    with AUDIO_TOKEN_LOCK:
        value = AUDIO_TOKENS.get(token)
        if not value:
            return None
        if value[0] <= time.time():
            AUDIO_TOKENS.pop(token, None)
            return None
        return value


def allowed_media_url(url):
    try:
        parsed = urlparse(url)
        return (parsed.scheme == "https" and parsed.hostname
                and parsed.hostname.lower().endswith(".googlevideo.com")
                and not parsed.username and not parsed.password and parsed.port in (None, 443))
    except ValueError:
        return False


class GoogleVideoRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Follow redirects only while every hop remains on HTTPS googlevideo."""
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not allowed_media_url(newurl):
            raise ApiError("UPSTREAM_HOST", "YT redirected the stream to an unsupported host.", 502)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def open_audio(url, range_header=None):
    if not allowed_media_url(url):
        raise ApiError("UPSTREAM_HOST", "The stream host is not permitted.", 502)
    request = urllib.request.Request(url, headers={"Accept": "*/*"})
    if range_header:
        request.add_header("Range", range_header)
    opener = urllib.request.build_opener(GoogleVideoRedirectHandler())
    return opener.open(request, timeout=STREAM_TIMEOUT)


def parse_audio_range(value):
    if value is None:
        return None
    if len(value) > 80:
        raise ApiError("RANGE", "Use one valid byte range.", 416)
    match = re.fullmatch(r"bytes=(\d*)-(\d*)", value.strip())
    if not match or not any(match.groups()) or (match.group(1) and match.group(2) and int(match.group(1)) > int(match.group(2))):
        raise ApiError("RANGE", "Use one valid byte range.", 416)
    return "bytes=" + match.group(1) + "-" + match.group(2)


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
            raise ApiError("NOT_FOUND", "YT returned no usable metadata.", 502)
        return info
    except ApiError:
        raise
    except Exception as error:
        message = str(error).lower()
        if "429" in message or "too many requests" in message:
            raise ApiError("RATE_LIMITED", "YT is limiting requests. Wait before retrying.", 429)
        if "sign in" in message or "bot" in message or "403" in message:
            raise ApiError("YOUTUBE_ACCESS", "YT refused access from this connection.", 502,
                           "This proof of concept does not use account cookies or route media through a proxy.")
        if "format" in message:
            raise ApiError("NOT_FOUND", "YT did not provide a supported direct audio format.", 502,
                           "Try another public video or update the local resolver. No relay fallback is used.")
        raise ApiError("YOUTUBE_ACCESS", "The public YT request could not complete.", 502,
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
        raise ApiError("INPUT", "Enter a search or a public YT video link (up to 180 characters).")
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
            raise ApiError("INPUT", "Use a public YT video link, video ID, or plain search words.")
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
    return {"items": items, "notice": f"{len(items)} results. " + ("Music search is a candidate list; Play verifies YT's Music category. " if rules["mode"] == "music" else "") + "Include matches any phrase; exclude wins. Rules inspect title and channel, not the audio."}


def resolve(body):
    rules = filters(body)
    video_id = body.get("id")
    if not isinstance(video_id, str) or not VIDEO_ID.fullmatch(video_id):
        raise ApiError("INPUT", "A valid YT video ID is required.")
    fresh = body.get("fresh", False)
    if not isinstance(fresh, bool):
        raise ApiError("INPUT", "fresh must be true or false.")
    if fresh:
        # Signatures may be tied to the former Wi-Fi/mobile public IP.
        with CACHE_LOCK:
            CACHE.pop("video:" + video_id, None)
    info = cache_get("video:" + video_id)
    if info is None:
        info = extract("https://www.youtube.com/watch?v=" + video_id)
        cache_put("video:" + video_id, info, 120)
    if not text_matches(info, rules):
        raise ApiError("FILTER", "This item no longer matches your include/exclude rules.", 422)
    if rules["mode"] == "music" and not music_category(info):
        raise ApiError("CATEGORY", "This video is not labelled Music by YT.", 422,
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
            raise ApiError("CATEGORY", "This video is not labelled Music by YT.", 422)
        host, expiry = direct_url(info.get("url"))
        cache_put("video:" + video_id, info, 120)
    ext = info.get("ext")
    if info.get("vcodec") != "none" or ext not in ("m4a", "webm"):
        raise ApiError("UNSUPPORTED", "No supported audio-only HTTPS stream was returned.", 502)
    return {"id": video_id, "title": info.get("title"), "channel": info.get("channel") or info.get("uploader"),
            "url": info["url"], "mime": "audio/mp4" if ext == "m4a" else "audio/webm",
            "host": host, "expiresAt": dt.datetime.fromtimestamp(expiry, dt.timezone.utc).isoformat() if expiry else None,
            "duration": info.get("duration"), "sourceUrl": "https://www.youtube.com/watch?v=" + video_id,
            "categories": info.get("categories") or [], "matchBasis": "YT Music category + title/channel rules" if rules["mode"] == "music" else "Title/channel rules",
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
        script = "'self' 'unsafe-inline'" if self.path.split("?")[0] in ("/report.html", "/certificate.html", "/phone.html", "/phone-help.html") else "'self'"
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src " + script + "; style-src 'self' 'unsafe-inline'; img-src 'self' https://i.ytimg.com data:; media-src 'self' https://*.googlevideo.com; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; worker-src 'self'; manifest-src 'self'")
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
        if path.startswith("/api/audio/") and getattr(self.server, "runtime", "") == "android-local":
            self.stream_audio(path, head=False)
            return
        if path in ("/download-certificate", "/blackyt-local-ca.cer"):
            certificate = PUBLIC / "blackyt-local-ca.cer"
            if not certificate.is_file():
                self.json(404, {"error": "Local certificate is not prepared.", "code": "NOT_FOUND"})
                return
            self.reply(200, certificate.read_bytes(), "application/octet-stream", download="blackyt-local-ca.cer")
            return
        if path == "/download-phone-guide":
            guide = PUBLIC / "phone.html"
            if not guide.is_file():
                self.json(404, {"error": "Phone setup guide is not prepared.", "code": "NOT_FOUND"})
                return
            self.reply(200, guide.read_bytes(), "text/html; charset=utf-8", download="BlackYT-Phone-Setup.html")
            return
        if path == "/api/status":
            try:
                version = importlib.metadata.version("yt-dlp")
                ready = True
            except importlib.metadata.PackageNotFoundError:
                version, ready = None, False
            phone = getattr(self.server, "runtime", "desktop-local") == "android-local"
            state = {"ok": ready, "resolver": "yt-dlp metadata only", "version": version,
                     "product": "BlackYT", "runtime": getattr(self.server, "runtime", "desktop-local"),
                     "directOnly": not phone, "mediaRelay": phone,
                     "androidLocalTest": "pending-device-test"}
            if phone:
                state.update(relayLocation="this-device", youtubeToDeviceOnly=True, externalMediaRelay=False)
            self.json(200, state)
            return
        if path == "/manifest.webmanifest" and getattr(self.server, "runtime", "") == "android-local":
            manifest = json.loads((PUBLIC / "manifest.webmanifest").read_text())
            manifest.update(name="BlackYT Phone", short_name="BlackYT Phone",
                            description="Phone-local player. Start Termux and run blackyt before listening.")
            self.reply(200, json.dumps(manifest).encode(), "application/manifest+json")
            return
        if path.startswith("/api/"):
            self.json(404, {"error": "No such route.", "code": "NOT_FOUND"})
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
                if getattr(self.server, "runtime", "") == "android-local":
                    result = phone_audio_result(result)
            else:
                raise ApiError("NOT_FOUND", "No such route.", 404)
            self.json(200, result)
        except ApiError as error:
            self.failure(error)
        except (ValueError, UnicodeDecodeError):
            self.failure(ApiError("INPUT", "Malformed JSON or request length."))
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception:
            self.failure(ApiError("INTERNAL", "The local resolver could not complete this request.", 500))

    def do_HEAD(self):
        if not self.valid_host():
            self.json(403, {"error": "Host not permitted.", "code": "HOST"})
            return
        path = urlparse(self.path).path
        if path.startswith("/api/audio/") and getattr(self.server, "runtime", "") == "android-local":
            self.stream_audio(path, head=True)
            return
        self.json(404, {"error": "No such route.", "code": "NOT_FOUND"})

    def stream_audio(self, path, *, head):
        if "?" in self.path or "/" in path.removeprefix("/api/audio/"):
            self.json(404, {"error": "Audio token not found.", "code": "NOT_FOUND"})
            return
        token = path.removeprefix("/api/audio/")
        entry = audio_token(token)
        if not entry:
            self.json(404, {"error": "Audio token expired or not found. Resolve again to retry.", "code": "AUDIO_TOKEN"})
            return
        try:
            range_header = parse_audio_range(self.headers.get("Range"))
        except ApiError as error:
            self.failure(error)
            return
        if not AUDIO_STREAMS.acquire(timeout=1):
            self.json(503, {"error": "Phone audio stream is busy. Retry shortly.", "code": "AUDIO_BUSY"})
            return
        response = None
        try:
            _expiry, url, expected_mime, _video_id = entry
            try:
                response = open_audio(url, range_header)
            except urllib.error.HTTPError as error:
                error.close()
                self.json(502, {"error": "YT refused the phone-local audio request. Resolve again to retry.", "code": "UPSTREAM_DENIED"})
                return
            except ApiError as error:
                self.failure(error)
                return
            except Exception:
                self.json(502, {"error": "The phone-local audio request could not complete.", "code": "UPSTREAM_ERROR"})
                return
            status = getattr(response, "status", response.getcode())
            content_type = response.headers.get_content_type()
            if status not in (200, 206) or content_type not in ("audio/mp4", "audio/webm") or content_type != expected_mime:
                self.json(502, {"error": "YT returned an unsupported audio response.", "code": "UPSTREAM_RESPONSE"})
                return
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            for name in ("Content-Length", "Content-Range"):
                value = response.headers.get(name)
                if value and re.fullmatch(r"(?:bytes )?[0-9*/ -]+", value):
                    self.send_header(name, value)
            self.send_header("Accept-Ranges", response.headers.get("Accept-Ranges", "bytes"))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("Content-Security-Policy", "default-src 'none'; media-src 'self'")
            self.end_headers()
            if not head:
                while True:
                    chunk = response.read(64 * 1024)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            if response:
                response.close()
            AUDIO_STREAMS.release()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--listen", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--allow-host", action="append", default=[])
    parser.add_argument("--cert", type=Path)
    parser.add_argument("--key", type=Path)
    parser.add_argument("--phone", action="store_true", help="Phone-local mode: loopback only; no LAN listener or TLS")
    args = parser.parse_args()
    if bool(args.cert) != bool(args.key):
        parser.error("--cert and --key must be supplied together")
    if args.phone and (args.listen != "127.0.0.1" or args.allow_host or args.cert or args.key):
        parser.error("--phone requires --listen 127.0.0.1, no --allow-host and no TLS certificate")
    server = ThreadingHTTPServer((args.listen, args.port), Handler)
    server.runtime = "android-local" if args.phone else "desktop-local"
    server.allowed_hosts = {"localhost", "127.0.0.1", "::1", args.listen, *args.allow_host}
    if args.cert:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(args.cert, args.key)
        server.socket = context.wrap_socket(server.socket, server_side=True)
    scheme = "https" if args.cert else "http"
    mode = "phone-local audio; no external relay" if args.phone else "metadata only; no media relay"
    print(f"BlackYT: {scheme}://{args.listen}:{args.port} — {mode}", flush=True)
    print("Use the same public network exit for resolver and player; direct URLs may be IP-bound.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        server.server_close()


if __name__ == "__main__":
    main()
