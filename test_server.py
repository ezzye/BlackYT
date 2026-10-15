"""Focused checks of filter decisions, URL boundaries, expiry refresh and HTTP routes."""
import copy
import json
import threading
import time
import tempfile
import subprocess
import sys
from pathlib import Path
import unittest
from http.client import HTTPConnection
from unittest.mock import patch
import server

ID = "aqz-KE-bpKQ"
def info(expiry=None):
    return {"id": ID, "title": "Carefree Jazz", "channel": "Example Music",
            "categories": ["Music"], "vcodec": "none", "ext": "m4a", "duration": 120,
            "url": "https://rr1.example.googlevideo.com/videoplayback?expire=" + str(expiry or int(time.time()) + 3600)}

class ResolverTests(unittest.TestCase):
    def setUp(self):
        server.CACHE.clear()

    def test_exclude_wins_and_include_is_case_insensitive_any_match(self):
        rules = server.filters({"include": ["JAZZ", "unmatched"], "exclude": []})
        self.assertTrue(server.text_matches(info(), rules))
        rules["exclude"] = ["carefree"]
        self.assertFalse(server.text_matches(info(), rules))
        rules["exclude"] = []; rules["include"] = ["tech interview"]
        self.assertFalse(server.text_matches(info(), rules))

    def test_music_category_does_not_trust_title(self):
        wrong = info(); wrong["categories"] = ["Education"]
        self.assertFalse(server.music_category(wrong))
        with patch.object(server, "extract", return_value=wrong):
            with self.assertRaises(server.ApiError) as caught:
                server.resolve({"id": ID, "mode": "music"})
        self.assertEqual(caught.exception.code, "CATEGORY")

    def test_direct_url_rejects_proxy_hosts_and_deceptive_suffixes(self):
        for url in ["http://rr1.googlevideo.com/a", "https://googlevideo.com.evil.test/a",
                    "https://evilgooglevideo.com/a", "https://localhost/a",
                    "https://user:password@rr1.googlevideo.com/a", "https://rr1.googlevideo.com:444/a"]:
            with self.subTest(url=url), self.assertRaises(server.ApiError):
                server.direct_url(url)
        self.assertEqual(server.direct_url(info()["url"])[0], "rr1.example.googlevideo.com")

    def test_expiring_cached_url_is_refreshed_once(self):
        server.cache_put("video:" + ID, info(int(time.time()) + 10), 120)
        fresh = info()
        with patch.object(server, "extract", return_value=fresh) as extractor:
            result = server.resolve({"id": ID, "mode": "music"})
        extractor.assert_called_once_with("https://www.youtube.com/watch?v=" + ID)
        self.assertEqual(result["url"], fresh["url"])

    def test_refresh_cannot_bypass_changed_filter_or_host(self):
        server.cache_put("video:" + ID, info(int(time.time()) + 10), 120)
        changed = info(); changed["categories"] = ["Education"]
        with patch.object(server, "extract", return_value=changed), self.assertRaises(server.ApiError) as error:
            server.resolve({"id": ID, "mode": "music"})
        self.assertEqual(error.exception.code, "CATEGORY")

    def test_fresh_request_avoids_cache_after_network_change(self):
        previous = info()
        server.cache_put("video:" + ID, previous, 120)
        changed = info(); changed["url"] += "&network=changed"
        with patch.object(server, "extract", return_value=changed) as extractor:
            result = server.resolve({"id": ID, "mode": "music", "fresh": True})
        extractor.assert_called_once_with("https://www.youtube.com/watch?v=" + ID)
        self.assertEqual(result["url"], changed["url"])
        changed["categories"] = ["Education"]
        with patch.object(server, "extract", return_value=changed), self.assertRaises(server.ApiError) as error:
            server.resolve({"id": ID, "mode": "music", "fresh": True})
        self.assertEqual(error.exception.code, "CATEGORY")
        with self.assertRaises(server.ApiError):
            server.resolve({"id": ID, "fresh": "true"})

    def test_phone_mode_cannot_listen_on_lan(self):
        for arguments in (["--listen", "0.0.0.0"], ["--allow-host", "example.test"]):
            result = subprocess.run([sys.executable, str(Path(server.__file__)), "--phone", *arguments], capture_output=True, text=True, timeout=5)
            self.assertEqual(result.returncode, 2)
            self.assertIn("--phone requires", result.stderr)

    def test_arbitrary_search_urls_never_reach_extractor(self):
        with patch.object(server, "extract") as extractor:
            for query in ["https://localhost/private", "http://169.254.169.254/latest/meta-data/", "https://evil.test/?v=" + ID]:
                with self.subTest(query=query), self.assertRaises(server.ApiError):
                    server.search({"query": query})
            extractor.assert_not_called()

    def test_supported_youtube_link_is_normalized_and_filtered(self):
        with patch.object(server, "extract", return_value=info()) as extractor:
            found = server.search({"query": "https://youtu.be/" + ID + "?si=tracking", "include": ["jazz"]})
        extractor.assert_called_once_with("https://www.youtube.com/watch?v=" + ID)
        self.assertEqual(len(found["items"]), 1)
        self.assertNotIn("si=tracking", found["items"][0]["url"])
        self.assertNotIn("googlevideo", json.dumps(found))

class HttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.http = server.ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        cls.http.allowed_hosts = {"127.0.0.1", "localhost"}
        cls.worker = threading.Thread(target=cls.http.serve_forever, daemon=True)
        cls.worker.start()

    @classmethod
    def tearDownClass(cls):
        cls.http.shutdown(); cls.http.server_close(); cls.worker.join()

    def call(self, path, method="GET", body=None, headers=None):
        conn = HTTPConnection("127.0.0.1", self.http.server_port, timeout=5)
        conn.request(method, path, body=body, headers=headers or {})
        response = conn.getresponse(); data = response.read(); result = (response.status, dict(response.getheaders()), data)
        conn.close(); return result

    def test_no_media_proxy_or_directory_traversal_route(self):
        for path in ("/api/proxy?url=https://rr1.googlevideo.com/a", "/media/a", "/%2e%2e/server.py"):
            with self.subTest(path=path):
                self.assertEqual(self.call(path)[0], 404)

    def test_api_metadata_not_cached_and_no_media_relay(self):
        status, headers, raw = self.call("/api/status")
        self.assertEqual(status, 200)
        self.assertEqual(headers["Cache-Control"], "no-store")
        self.assertFalse(json.loads(raw)["mediaRelay"])

    def test_phone_identity_and_manifest_are_separate_from_mac_icon(self):
        self.http.runtime = "android-local"
        try:
            status = json.loads(self.call("/api/status")[2])
            self.assertEqual(status["product"], "BlackYT")
            self.assertEqual(status["runtime"], "android-local")
            manifest = json.loads(self.call("/manifest.webmanifest")[2])
            self.assertEqual(manifest["short_name"], "BlackYT Phone")
            self.assertEqual(manifest["start_url"], "/")
        finally:
            self.http.runtime = "desktop-local"

    def test_certificate_is_a_named_download_not_an_inline_document(self):
        with tempfile.TemporaryDirectory() as folder:
            public = Path(folder)
            certificate = b"test certificate bytes"
            (public / "blackyt-local-ca.cer").write_bytes(certificate)
            with patch.object(server, "PUBLIC", public):
                status, headers, body = self.call("/download-certificate")
            self.assertEqual(status, 200)
            self.assertEqual(headers["Content-Type"], "application/octet-stream")
            self.assertEqual(headers["Content-Disposition"], 'attachment; filename="blackyt-local-ca.cer"')
            self.assertEqual(body, certificate)
            self.assertEqual(headers["Cache-Control"], "no-store")

    def test_host_and_cross_site_post_are_rejected(self):
        self.assertEqual(self.call("/api/status", headers={"Host": "attacker.test"})[0], 403)
        body = json.dumps({"query": "jazz"})
        self.assertEqual(self.call("/api/search", "POST", body, {"Content-Type": "application/json", "Origin": "https://attacker.test"})[0], 403)

    def test_bad_content_type_and_invalid_ids_are_bounded(self):
        self.assertEqual(self.call("/api/resolve", "POST", "{}", {"Content-Type": "text/plain"})[0], 415)
        self.assertEqual(self.call("/api/resolve", "POST", '{"id":"bad"}', {"Content-Type": "application/json"})[0], 400)

if __name__ == "__main__":
    unittest.main(verbosity=2)
