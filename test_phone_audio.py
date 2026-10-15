"""Phone-local audio route checks; upstream networking is always mocked."""
import io
import json
import threading
import time
import unittest
from email.message import Message
from http.client import HTTPConnection
from unittest.mock import patch

import server

VIDEO_ID = "aqz-KE-bpKQ"
SIGNED = "https://rr1.example.googlevideo.com/videoplayback?expire=" + str(int(time.time()) + 3600)


def resolved():
    return {"id": VIDEO_ID, "title": "Carefree", "channel": "Example", "url": SIGNED,
            "mime": "audio/mp4", "host": "rr1.example.googlevideo.com", "expiresAt": None,
            "directOnly": True}


class FakeResponse:
    status = 206

    def __init__(self, body=b"audio-bytes", content_type="audio/mp4"):
        self.headers = Message()
        self.headers["Content-Type"] = content_type
        self.headers["Content-Length"] = str(len(body))
        self.headers["Content-Range"] = "bytes 2-12/100"
        self.headers["Accept-Ranges"] = "bytes"
        self.body = io.BytesIO(body)
        self.read_sizes = []
        self.closed = False

    def getcode(self): return self.status
    def read(self, size=-1):
        self.read_sizes.append(size)
        return self.body.read(size)
    def close(self): self.closed = True


class PhoneAudioTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.http = server.ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        cls.http.allowed_hosts = {"127.0.0.1", "localhost"}
        cls.http.runtime = "android-local"
        cls.worker = threading.Thread(target=cls.http.serve_forever, daemon=True)
        cls.worker.start()

    @classmethod
    def tearDownClass(cls):
        cls.http.shutdown(); cls.http.server_close(); cls.worker.join()

    def setUp(self):
        with server.AUDIO_TOKEN_LOCK:
            server.AUDIO_TOKENS.clear()

    def call(self, path, method="GET", body=None, headers=None):
        conn = HTTPConnection("127.0.0.1", self.http.server_port, timeout=5)
        conn.request(method, path, body=body, headers=headers or {})
        response = conn.getresponse()
        data = response.read()
        result = response.status, dict(response.getheaders()), data
        conn.close()
        return result

    def resolve_http(self):
        body = json.dumps({"id": VIDEO_ID, "mode": "interests"})
        status, _headers, raw = self.call("/api/resolve", "POST", body, {"Content-Type": "application/json"})
        self.assertEqual(status, 200)
        return json.loads(raw)

    def test_phone_resolve_returns_opaque_local_token_without_signed_url(self):
        with patch.object(server, "resolve", return_value=resolved()):
            data = self.resolve_http()
        self.assertRegex(data["url"], r"^/api/audio/[A-Za-z0-9_-]+$")
        self.assertNotIn(SIGNED, json.dumps(data))
        self.assertEqual(data["sourceHost"], "rr1.example.googlevideo.com")
        self.assertEqual(data["transport"], "phone-local")
        self.assertTrue(data["youtubeToDeviceOnly"])
        self.assertFalse(data["externalMediaRelay"])

    def test_phone_status_describes_on_device_transport(self):
        data = json.loads(self.call("/api/status")[2])
        self.assertEqual(data["runtime"], "android-local")
        self.assertFalse(data["directOnly"])
        self.assertTrue(data["mediaRelay"])
        self.assertEqual(data["relayLocation"], "this-device")
        self.assertTrue(data["youtubeToDeviceOnly"])
        self.assertFalse(data["externalMediaRelay"])

    def test_range_stream_is_forwarded_and_chunked(self):
        data = server.phone_audio_result(resolved())
        token = data["url"].split("/")[-1]
        upstream = FakeResponse(body=b"0123456789abc")
        with patch.object(server, "open_audio", return_value=upstream) as opener:
            status, headers, body = self.call("/api/audio/" + token, headers={"Range": "bytes=2-12"})
        self.assertEqual(status, 206)
        self.assertEqual(body, b"0123456789abc")
        self.assertEqual(headers["Content-Range"], "bytes 2-12/100")
        self.assertEqual(headers["Accept-Ranges"], "bytes")
        self.assertEqual(opener.call_args.args, (SIGNED, "bytes=2-12"))
        self.assertTrue(upstream.read_sizes and max(upstream.read_sizes) == 64 * 1024)
        self.assertTrue(upstream.closed)

    def test_invalid_range_and_token_never_open_upstream(self):
        with patch.object(server, "open_audio") as opener:
            self.assertEqual(self.call("/api/audio/not-a-token")[0], 404)
            data = server.phone_audio_result(resolved())
            token = data["url"].split("/")[-1]
            self.assertEqual(self.call("/api/audio/" + token, headers={"Range": "bytes=1-2,4-5"})[0], 416)
            self.assertEqual(self.call("/api/audio/" + token + "?url=https://attacker.test")[0], 404)
            opener.assert_not_called()

    def test_head_probes_upstream_metadata_without_body(self):
        data = server.phone_audio_result(resolved())
        token = data["url"].split("/")[-1]
        upstream = FakeResponse()
        with patch.object(server, "open_audio", return_value=upstream) as opener:
            status, _headers, body = self.call("/api/audio/" + token, method="HEAD")
        self.assertEqual(status, 206)
        self.assertEqual(body, b"")
        opener.assert_called_once_with(SIGNED, None)
        self.assertTrue(upstream.closed)

    def test_redirect_handler_blocks_every_non_googlevideo_hop(self):
        handler = server.GoogleVideoRedirectHandler()
        request = type("Request", (), {"get_method": lambda self: "GET"})()
        with self.assertRaises(server.ApiError) as caught:
            handler.redirect_request(request, None, 302, "Found", {}, "http://127.0.0.1/private")
        self.assertEqual(caught.exception.code, "UPSTREAM_HOST")

    def test_default_desktop_resolve_still_returns_direct_url_without_audio_endpoint(self):
        self.http.runtime = "desktop-local"
        try:
            with patch.object(server, "resolve", return_value=resolved()):
                body = json.dumps({"id": VIDEO_ID, "mode": "interests"})
                data = json.loads(self.call("/api/resolve", "POST", body, {"Content-Type": "application/json"})[2])
            self.assertEqual(data["url"], SIGNED)
            self.assertTrue(data["directOnly"])
            self.assertEqual(self.call("/api/audio/anything")[0], 404)
        finally:
            self.http.runtime = "android-local"


if __name__ == "__main__":
    unittest.main(verbosity=2)
