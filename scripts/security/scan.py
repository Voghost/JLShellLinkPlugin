#!/usr/bin/env python3
"""Pinned, fail-closed delivery scans. Never persist raw secret findings."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request

VERSION = '0.75.0'
BINARIES = {
    ('Linux', 'x86_64'): ('Linux-64bit', 'c6e65abddb348e25f10549df887045629cf28cc72453cd1c63acb717316b3f3f'),
    ('Darwin', 'arm64'): ('macOS-ARM64', '4a77108cccf8e55c8d6823e1e759939a622277e66cd0daa3c1fc621ed69e4568'),
}


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def scanner(directory):
    key = (platform.system(), platform.machine())
    if key not in BINARIES:
        raise ValueError('Run delivery security scans on Linux x64 or macOS ARM64')
    name, expected = BINARIES[key]
    url = f'https://github.com/aquasecurity/trivy/releases/download/v{VERSION}/trivy_{VERSION}_{name}.tar.gz'
    with urllib.request.urlopen(url, timeout=120) as response:
        archive = response.read()
    if hashlib.sha256(archive).hexdigest() != expected:
        raise ValueError('Scanner download checksum mismatch')
    with tarfile.open(fileobj=io.BytesIO(archive), mode='r:gz') as bundle:
        member = bundle.getmember('trivy')
        if not member.isfile():
            raise ValueError('Scanner archive contains no regular binary')
        binary = directory / 'trivy'
        binary.write_bytes(bundle.extractfile(member).read())
        binary.chmod(0o700)
    return str(binary)


def run_scan(binary, command, target, raw, cache, scanners):
    config = raw.parent / 'trivy-empty.yaml'
    config.write_text('{}\n')
    ignore = raw.parent / 'empty.ignore'
    ignore.write_text('')
    result = subprocess.run([
        binary, command, '--quiet', '--skip-version-check', '--timeout', '10m',
        '--cache-dir', str(cache), '--scanners', scanners,
        '--config', str(config), '--ignorefile', str(ignore),
        '--ignore-unfixed=false', '--severity', 'UNKNOWN,LOW,MEDIUM,HIGH,CRITICAL',
        '--format', 'json', '--output', str(raw),
        *(['--input'] if command == 'image' else []), str(target),
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if result.returncode != 0 or not raw.is_file():
        raise ValueError(f'{command} scanner failed; no release allowed (exit {result.returncode})')
    data = json.loads(raw.read_text())
    if not isinstance(data, dict) or data.get('SchemaVersion') != 2:
        raise ValueError('Unexpected scanner report schema')
    if not isinstance(data.get('Results', []), list):
        raise ValueError('Invalid scanner results')
    return data


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sbom', action='append', default=[])
    parser.add_argument('--image-archive', action='append', default=[])
    parser.add_argument('--secrets', action='store_true')
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    if not (args.sbom or args.image_archive or args.secrets):
        parser.error('At least one scan target is required')
    output = Path(args.output)
    output.unlink(missing_ok=True)
    root = Path(subprocess.check_output(['git', 'rev-parse', '--show-toplevel'], text=True).strip())
    report = {'schemaVersion': 1, 'scanner': f'trivy/{VERSION}',
              'sourceRevision': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
              'checks': [], 'passed': False}
    failed = False
    with tempfile.TemporaryDirectory(prefix='jlshell-security-') as temporary:
        private = Path(temporary)
        os.chmod(private, 0o700)
        binary = scanner(private)
        cache = Path(os.environ.get('JLSHELL_SECURITY_CACHE', str(private / 'cache')))
        raw = private / 'raw.json'
        if args.secrets:
            snapshot = private / 'source'
            snapshot.mkdir()
            # Scan the tracked working tree, including release-time POM version changes.
            # No local ignored credentials, build products or .git data are copied.
            files = subprocess.check_output(['git', '-C', str(root), 'ls-files', '-z']).split(b'\0')
            for entry in files:
                if not entry:
                    continue
                relative = Path(os.fsdecode(entry))
                source = root / relative
                if source.is_symlink():
                    raise ValueError('Tracked symlinks require security review')
                if source.is_file():
                    destination = snapshot / relative
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(source, destination)
            data = run_scan(binary, 'fs', snapshot, raw, cache, 'secret')
            findings = [s for r in data.get('Results', []) for s in r.get('Secrets', [])]
            # Deliberately omit matches, snippets, line text and host paths.
            report['checks'].append({'type': 'secrets', 'count': len(findings),
                                     'ruleIds': sorted({s['RuleID'] for s in findings})})
            failed |= bool(findings)
            raw.unlink()
        for kind, targets in [('sbom', args.sbom), ('image', args.image_archive)]:
            for target in targets:
                path = Path(target)
                if not path.is_file():
                    raise ValueError('Required delivery scan input is missing')
                if kind == 'sbom':
                    bom = json.loads(path.read_text())
                    if bom.get('bomFormat') != 'CycloneDX' or not isinstance(bom.get('components'), list):
                        raise ValueError('Invalid CycloneDX delivery inventory')
                data = run_scan(binary, kind, path, raw, cache, 'vuln')
                findings = [{'id': v['VulnerabilityID'], 'package': v['PkgName'],
                             'installed': v['InstalledVersion'], 'fixed': v.get('FixedVersion', ''),
                             'severity': v['Severity']}
                            for r in data.get('Results', []) for v in r.get('Vulnerabilities', [])
                            if v['Severity'] in ('HIGH', 'CRITICAL')]
                report['checks'].append({'type': kind, 'input': path.name,
                                         'sha256': digest(path),
                                         'blockingVulnerabilities': findings})
                failed |= bool(findings)
                raw.unlink()
    report['passed'] = not failed
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + '\n')
    print(f'Delivery security: {"FAIL" if failed else "PASS"}; sanitized report: {output.name}')
    return 1 if failed else 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError, tarfile.TarError, subprocess.SubprocessError):
        # Network and parser exceptions can contain URLs or input text. Keep logs safe.
        print('Delivery security scan failed; publishing is blocked. Check scanner access and input format.')
        raise SystemExit(2)
