#!/usr/bin/env python3
"""Require complete Drive and OneDrive readback receipts before marking a backup complete."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def read_lines(path):
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding='utf-8-sig').splitlines() if line.strip()]


def verify_onedrive_report(report, expected, transfer_receipt=None):
    if report.get('verified') is not True:
        return False
    files = report.get('files', [])
    received = {item['relative_path']: item for item in files}
    allowed = dict(expected)
    if transfer_receipt is not None and 'transfer-receipt.json' in received:
        data = transfer_receipt.read_bytes()
        allowed['transfer-receipt.json'] = {'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
    if len(received) != len(files) or set(received) != set(allowed):
        raise ValueError('OneDrive readback does not exactly cover this snapshot manifest')
    for path, original in allowed.items():
        if (received[path]['size'], received[path]['sha256']) != (original['size'], original['sha256']):
            raise ValueError('OneDrive readback differs from the original manifest: ' + path)
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    root = args.directory
    manifest = read_json(root / 'individual-files.filelist.json')
    expected = {f['relative_path']: f for f in manifest['files']}
    uploads = {}
    ids = {}
    for name in ('google-drive-files.jsonl', 'google-drive-sept26-files.jsonl', 'google-drive-sept27-files.jsonl'):
        for f in read_lines(root / name):
            if not (f.get('upload_confirmed') is True or f.get('status') == 'uploaded'):
                raise ValueError('An upload has not been confirmed')
            path = f['relative_path']
            if path not in expected or (f['size'], f['sha256']) != (expected[path]['size'], expected[path]['sha256']):
                raise ValueError('Upload receipt differs from original manifest')
            if path in uploads and uploads[path]['id'] != f['id']:
                raise ValueError('Multiple cloud files exist for the same original path')
            if f['id'] in ids and ids[f['id']] != path:
                raise ValueError('Cloud file ID reused for different original paths')
            uploads[path] = f
            ids[f['id']] = path
    readbacks = {}
    for f in read_lines(root / 'google-drive-readback.jsonl'):
        path = f['relative_path']
        if path not in expected or f.get('verified') is not True:
            raise ValueError('Unexpected or unsuccessful readback')
        original = expected[path]
        uploaded = uploads.get(path)
        if not uploaded or (f['id'], f['size'], f['sha256']) != (uploaded['id'], original['size'], original['sha256']):
            raise ValueError('Readback receipt differs from uploaded original')
        readbacks[path] = f
    onedrive_path = root / 'onedrive-transfer-receipt.json'
    onedrive = read_json(onedrive_path) if onedrive_path.exists() else {}
    readback_path = root / 'onedrive-readback.json'
    onedrive_readback = read_json(readback_path) if readback_path.exists() else {}
    transfer_path = root / 'transfer-receipt.json'
    onedrive_verified = (onedrive.get('cloud_sha256_readback_verified') is True
        and verify_onedrive_report(onedrive_readback, expected, transfer_path if transfer_path.exists() else None))
    complete = set(uploads) == set(expected) == set(readbacks) and onedrive_verified
    summary = {'complete': complete, 'expected_files': len(expected), 'recordings': manifest['segment_count'],
               'cumulative_snapshot_recordings': manifest.get('snapshot_segment_count', manifest['segment_count']),
               'reused_recordings': manifest.get('reused_segment_count', 0),
               'google_uploaded': len(uploads), 'google_readback_verified': len(readbacks),
               'onedrive_readback_verified': onedrive_verified}
    if args.write:
        if not complete:
            raise ValueError('Cannot write completion receipt while files remain pending')
        google_transfer = read_json(root / 'google-drive-transfer.json')
        if not google_transfer.get('folder_url') or not onedrive.get('cloud_url'):
            raise ValueError('Cloud folder URLs must be recorded before writing completion')
        report = {**summary, 'completed_at_utc': datetime.now(timezone.utc).isoformat(),
                  'original_archive_name': manifest['original_archive_name'],
                  'original_archive_size_bytes': manifest['original_archive_size_bytes'],
                  'original_archive_sha256': manifest['original_archive_sha256'],
                  'source_filelist_sha256': hashlib.sha256((root / 'individual-files.filelist.json').read_bytes()).hexdigest(),
                  'google_readback_journal_sha256': hashlib.sha256((root / 'google-drive-readback.jsonl').read_bytes()).hexdigest(),
                  'onedrive_readback_report_sha256': hashlib.sha256(readback_path.read_bytes()).hexdigest(),
                  'payload_bytes': manifest['output_bytes'], 'originals_preserved': True,
                  'snapshot_excluded_active_segment': manifest['verification']['excluded_partial_segments'],
                  'google_folder_url': google_transfer['folder_url'],
                  'onedrive_folder_url': onedrive['cloud_url'],
                  'laptop_directory': manifest['output_directory'],
                  'verification_scope': 'Byte consistency of copied files; not speaker identity, timestamp accuracy, or uninterrupted capture',
                  'google_files': [{'relative_path': p, 'id': uploads[p]['id'], 'size': expected[p]['size'], 'sha256': expected[p]['sha256']} for p in sorted(expected)]}
        with (root / 'backup-completion.json').open('x', encoding='utf-8') as stream:
            json.dump(report, stream, indent=2)
            stream.write('\n')
    print(json.dumps(summary))


if __name__ == '__main__':
    main()
