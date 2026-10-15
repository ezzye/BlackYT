#!/usr/bin/env python3
"""Prepare an optional private-Wi-Fi TLS certificate; never installs device trust."""
import argparse
import datetime
import ipaddress
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent

def prepare(address):
    ip = ipaddress.ip_address(address)
    if ip.version != 4 or not ip.is_private or ip.is_unspecified:
        raise ValueError("Use this Mac's private IPv4 Wi-Fi address.")
    openssl = shutil.which("openssl")
    if not openssl:
        raise RuntimeError("OpenSSL is required to prepare local HTTPS.")
    private = ROOT / ".tls"
    private.mkdir(mode=0o700, exist_ok=True)
    if (private / "server-key.pem").exists():
        raise RuntimeError("Local TLS is already prepared. Existing keys were preserved.")
    certificate = ROOT / "public" / "blackyt-local-ca.crt"
    env = os.environ.copy()
    def run(*args):
        result = subprocess.run([openssl, *map(str, args)], capture_output=True, env=env)
        if result.returncode:
            raise RuntimeError("OpenSSL could not prepare local HTTPS: " + result.stderr.decode()[-300:])
    with tempfile.TemporaryDirectory(prefix="blackyt-cert-") as folder:
        temp = Path(folder)
        ca_key, csr, extensions = temp / "ca-key.pem", temp / "server.csr", temp / "server.ext"
        run("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", ca_key,
            "-out", certificate, "-sha256", "-days", "14", "-subj", "/CN=BlackYT local test CA - remove after testing",
            "-addext", "basicConstraints=critical,CA:TRUE,pathlen:0",
            "-addext", "keyUsage=critical,keyCertSign,cRLSign")
        run("req", "-new", "-newkey", "rsa:2048", "-nodes", "-keyout", private / "server-key.pem",
            "-out", csr, "-subj", "/CN=BlackYT private Wi-Fi proof of concept")
        (private / "server-key.pem").chmod(0o600)
        extensions.write_text("basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=IP:" + str(ip) + ",IP:127.0.0.1,DNS:localhost\n")
        run("x509", "-req", "-in", csr, "-CA", certificate, "-CAkey", ca_key,
            "-set_serial", str(secrets.randbits(150)), "-out", private / "server-cert.pem",
            "-days", "7", "-sha256", "-extfile", extensions)
        run("x509", "-in", certificate, "-outform", "DER", "-out", ROOT / "public" / "blackyt-local-ca.cer")
        # The CA signing key is temporary and is removed with this directory.
        # Only the server key remains on this Mac. It is never served or packaged.
    fingerprint = subprocess.check_output([openssl, "x509", "-in", str(certificate), "-noout", "-fingerprint", "-sha256"], text=True).strip()
    dates = subprocess.check_output([openssl, "x509", "-in", str(private / "server-cert.pem"), "-noout", "-dates"], text=True).strip()
    data = {"lanAddress": str(ip), "httpsUrl": f"https://{ip}:8766", "publicCertificate": certificate.name,
            "caSha256": fingerprint.split("=", 1)[-1], "serverValidity": dates,
            "createdAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "deviceTrustInstalled": False, "caSigningKeyRetained": False,
            "privateServerKey": "Kept only on this Mac; excluded from source package"}
    (private / "config.json").write_text(json.dumps(data, indent=2))
    print(json.dumps(data, indent=2))

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ip", required=True)
    prepare(parser.parse_args().ip)
