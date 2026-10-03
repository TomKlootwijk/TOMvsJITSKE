#!/usr/bin/env python3
"""Hash every file in a cloud folder download against the local organized manifest."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path, PurePosixPath
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('download', type=Path)
    parser.add_argument('manifest', type=Path)
    parser.add_argument('--prefix', required=True)
    parser.add_argument('--transfer-receipt', type=Path)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding='utf-8'))
    expected = {f['relative_path']: (f['size'], f['sha256']) for f in manifest['files']}
    if args.transfer_receipt:
        data = args.transfer_receipt.read_bytes()
        expected['transfer-receipt.json'] = (len(data), hashlib.sha256(data).hexdigest())
    prefix = args.prefix.rstrip('/') + '/'
    verified = []
    with zipfile.ZipFile(args.download) as archive:
        members = {}
        for info in archive.infolist():
            if info.is_dir():
                continue
            if not info.filename.startswith(prefix):
                raise ValueError('Unexpected archive prefix')
            relative = info.filename[len(prefix):]
            if '\\' in relative or PurePosixPath(relative).is_absolute() or '..' in PurePosixPath(relative).parts:
                raise ValueError('Unsafe archive path')
            if relative in members:
                raise ValueError('Duplicate archive path')
            members[relative] = info
        if set(members) != set(expected):
            raise ValueError(f'File coverage mismatch: missing={len(set(expected)-set(members))}, unexpected={len(set(members)-set(expected))}')
        for relative, (size, digest) in expected.items():
            info = members[relative]
            if info.file_size != size:
                raise ValueError(f'Incorrect size: {relative}')
            actual = hashlib.sha256()
            total = 0
            with archive.open(info) as stream:
                while block := stream.read(1024 * 1024):
                    actual.update(block)
                    total += len(block)
            if total != size or actual.hexdigest() != digest:
                raise ValueError(f'Incorrect bytes: {relative}')
            verified.append({'relative_path': relative, 'size': total, 'sha256': digest})
    report = {'verified': True, 'method': 'Complete cloud folder download; all member bytes SHA-256 checked against verified local originals',
              'verified_at_utc': datetime.now(timezone.utc).isoformat(), 'download': str(args.download.resolve()),
              'manifest': str(args.manifest.resolve()), 'verified_files': len(verified),
              'verified_audio_files': sum(f['relative_path'].endswith('.aac') for f in verified),
              'verified_bytes': sum(f['size'] for f in verified), 'files': verified}
    with args.report.open('x', encoding='utf-8') as stream:
        json.dump(report, stream, indent=2)
        stream.write('\n')
    print(json.dumps({key: value for key, value in report.items() if key != 'files'}))


if __name__ == '__main__':
    main()
