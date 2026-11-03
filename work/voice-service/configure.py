#!/usr/bin/env python3
"""Create private broker TLS/pairing files. Does not start a service or contact OpenAI."""
from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import ssl
import subprocess
import tempfile
from urllib.parse import urlsplit


def endpoint_host(endpoint):
    try:
        parsed = urlsplit(endpoint)
        host = parsed.hostname
        port = parsed.port
    except ValueError:
        raise ValueError('Invalid HTTPS endpoint') from None
    if (parsed.scheme != 'https' or not host or parsed.username is not None or parsed.password is not None
            or parsed.query or parsed.fragment or parsed.path not in ('', '/') or (port is not None and not 1 <= port <= 65535)):
        raise ValueError('Endpoint must be an HTTPS origin without credentials or a path')
    try:
        ipaddress.ip_address(host)
        san = 'IP:' + host
    except ValueError:
        if not re.fullmatch(r'[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?', host):
            raise ValueError('Invalid endpoint hostname') from None
        san = 'DNS:' + host
    return endpoint.rstrip('/'), san


def write_private(path, data):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
    except BaseException:
        path.unlink(missing_ok=True)
        raise


def cert_sha256(path):
    # Exact certificate pin; native clients must still verify it on every connection.
    der = ssl.PEM_cert_to_DER_cert(Path(path).read_text(encoding='ascii'))
    return hashlib.sha256(der).hexdigest()


def configure(directory: Path, endpoint: str, api_key_file: Path | None = None,
              allow_control=False, pairing_read_only=True):
    endpoint, san = endpoint_host(endpoint)
    directory = directory.absolute()
    if directory.is_symlink():
        raise ValueError('Private directory must not be a symlink')
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = directory.stat()
    if info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise ValueError('Private directory must be owned by this user with mode 0700')
    names = ['device-token.txt', 'tls-key.pem', 'tls-cert.pem', 'config.json', 'tablet-voice-setup.json']
    if any((directory / name).exists() or (directory / name).is_symlink() for name in names):
        raise ValueError('Private setup already exists; refusing to overwrite pairing or TLS identity')
    if api_key_file is not None:
        api_key_file = api_key_file.absolute()
        if not api_key_file.is_file() or api_key_file.is_symlink():
            raise ValueError('API key file must be a regular private file')
        info = api_key_file.stat()
        if info.st_uid != os.getuid() or info.st_mode & 0o077:
            raise ValueError('API key file must be owned by this user with mode 0600')
    previous_umask = os.umask(0o077)
    created = []
    try:
        # The token is never passed in a command argument, printed, or embedded in source.
        token = secrets.token_urlsafe(32)
        write_private(directory / 'device-token.txt', token + '\n')
        created.append(directory / 'device-token.txt')
        key, cert = directory / 'tls-key.pem', directory / 'tls-cert.pem'
        created.extend([key, cert])
        # A config file works with macOS LibreSSL as well as OpenSSL versions without -addext.
        with tempfile.NamedTemporaryFile(mode='w', dir=directory, suffix='.openssl.cnf') as cfg:
            cfg.write('[req]\nprompt=no\ndistinguished_name=dn\n'
                      '[dn]\nCN=Home Pad Voice\n[v3_req]\n'
                      'basicConstraints=critical,CA:FALSE\n'
                      'keyUsage=critical,digitalSignature,keyEncipherment\n'
                      'extendedKeyUsage=serverAuth\nsubjectAltName=' + san + '\n')
            cfg.flush()
            subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:3072', '-sha256', '-nodes',
                            '-keyout', str(key), '-out', str(cert), '-days', '365',
                            '-config', cfg.name, '-extensions', 'v3_req'],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        key.chmod(0o600)
        cert.chmod(0o600)
        fingerprint = cert_sha256(cert)
        config = {
            'provider': 'codex',
            'device_token_file': 'device-token.txt',
            'api_key_file': str(api_key_file) if api_key_file else None,
            'certificate_file': 'tls-cert.pem', 'private_key_file': 'tls-key.pem',
            'model': 'gpt-live-1', 'backend_model': 'gpt-6-luna',
            'allow_control': bool(allow_control), 'sessions_per_minute': 3,
        }
        write_private(directory / 'config.json', json.dumps(config, indent=2) + '\n')
        created.append(directory / 'config.json')
        pairing = {'endpoint': endpoint, 'cert_sha256': fingerprint, 'token': token,
                   'readOnly': bool(pairing_read_only)}
        write_private(directory / 'tablet-voice-setup.json', json.dumps(pairing, indent=2) + '\n')
        created.append(directory / 'tablet-voice-setup.json')
        return {'config_path': str(directory / 'config.json'),
                'pairing_path': str(directory / 'tablet-voice-setup.json'),
                'cert_sha256': fingerprint, 'read_only': bool(pairing_read_only)}
    except Exception:
        # Remove only the known files this new attempt created; never touch an existing setup.
        for path in reversed(created):
            path.unlink(missing_ok=True)
        raise
    finally:
        os.umask(previous_umask)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, default=Path(__file__).parent / 'private')
    parser.add_argument('--endpoint', required=True, help='HTTPS origin used by the tablet over Wi-Fi')
    parser.add_argument('--api-key-file', type=Path, help='Existing owned mode-0600 key file; never a key value')
    parser.add_argument('--allow-control', action='store_true', help='Allow native validated power tools on the server')
    parser.add_argument('--read-write-pairing', action='store_true', help='Export pairing with readOnly=false')
    args = parser.parse_args()
    try:
        result = configure(args.directory, args.endpoint, args.api_key_file, args.allow_control,
                           not args.read_write_pairing)
    except (OSError, ValueError, subprocess.SubprocessError, ssl.SSLError):
        raise SystemExit('Private voice setup failed; check file permissions, endpoint and OpenSSL. No credentials printed.') from None
    print('Private voice setup created. Keep config and pairing files local; no service was started.')
    print('Configuration file: ' + result['config_path'])
    print('Tablet pairing file: ' + result['pairing_path'])
    print('Certificate SHA256: ' + result['cert_sha256'])


if __name__ == '__main__':
    main()
