#!/usr/bin/env python3
"""Build a checksum-pinned phone bundle; include only runtime files."""
import argparse
import gzip
import hashlib
import html
import io
import json
from pathlib import Path
import shlex
import tarfile
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ['server.py', 'public/index.html', 'public/app.js', 'public/sw.js',
           'public/manifest.webmanifest', 'public/phone-help.html',
           'public/icons/icon.svg', 'public/icons/icon-192.png', 'public/icons/icon-512.png',
           'phone/blackyt.sh', 'phone/requirements.txt']
def digest(data): return hashlib.sha256(data).hexdigest()
def build(transfer_url, destination=None):
    url = urlparse(transfer_url)
    if url.scheme not in ('http', 'https') or not url.hostname or url.username or url.password or url.query or url.fragment:
        raise ValueError('Use an HTTP(S) transfer origin without credentials, query or fragment')
    base = transfer_url.rstrip('/')
    public_root = Path(destination) if destination else ROOT / 'public'
    output = public_root / 'phone'
    output.mkdir(parents=True, exist_ok=True)
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode='w', format=tarfile.USTAR_FORMAT) as archive:
        for name in RUNTIME:
            path = ROOT / name
            data = path.read_bytes()
            member = tarfile.TarInfo('app/' + name)
            member.size = len(data); member.mtime = 0; member.mode = 0o600
            archive.addfile(member, io.BytesIO(data))
    blob = gzip.compress(raw.getvalue(), mtime=0)
    checksum = digest(blob)
    bundle_name = f'blackyt-phone-{checksum[:16]}.tar.gz'
    (output / bundle_name).write_bytes(blob)
    installer = (ROOT / 'phone/install.sh').read_text().replace('__BUNDLE_SHA256__', checksum).encode()
    installer_sha = digest(installer)
    installer_name = f'install-{installer_sha[:16]}.sh'
    (output / installer_name).write_bytes(installer)
    target = '"$TMPDIR/blackyt-install.sh"'
    command = (f'apt update && apt full-upgrade -y && apt install -y curl && curl --fail --location --retry 2 --max-time 120 '
               f'{shlex.quote(base + "/phone/" + installer_name)} -o {target} && '
               f'printf \'%s  %s\\n\' {shlex.quote(installer_sha)} {target} | sha256sum -c - && '
               f'bash {target} {shlex.quote(base + "/phone/" + bundle_name)} && blackyt')
    guide = (ROOT / 'phone/guide.template.html').read_text()
    values = {'GUIDE_URL': base + '/phone.html', 'COMMAND_HTML': command,
              'INSTALL_SHA': installer_sha, 'BUNDLE_SHA': checksum, 'BUILD': checksum[:16]}
    for key, value in values.items():
        guide = guide.replace('__' + key + '__', html.escape(value, quote=True))
    (public_root / 'phone.html').write_text(guide)
    metadata = {'build': checksum[:16], 'bundle': bundle_name, 'bundleSha256': checksum,
                'installer': installer_name, 'installerSha256': installer_sha,
                'transferOrigin': base, 'runtimeOrigin': 'http://127.0.0.1:8765',
                'files': RUNTIME, 'command': command, 'mediaRelay': True, 'relayLocation': 'this-device', 'externalMediaRelay': False,
                'deviceTest': 'Owner confirmed Pixel phone-local playback and manual queue; two-minute screen-lock playback confirmed; locked queue transition not separately confirmed'}
    (output / 'build.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print(json.dumps({k:v for k,v in metadata.items() if k not in ('files','command')}, indent=2))
    return metadata
if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--transfer-url', required=True)
    parser.add_argument('--output', type=Path, help='Optional build destination; defaults to public/')
    args = parser.parse_args()
    build(args.transfer_url, args.output)
