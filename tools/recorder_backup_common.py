"""Small filesystem primitives shared by the standalone recorder backup."""
import hashlib
import json
import os
import subprocess
import time
import uuid
from pathlib import Path
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / 'evidence'

class Pending(RuntimeError):
    """A normal resumable condition, never a success claim."""

def utc():
    return datetime.now(timezone.utc).isoformat()

def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))

def sha(path):
    with Path(path).open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()

def atomic(path, value):
    """Only for mutable state; evidence artifacts use publish instead."""
    path = Path(path); path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    with temp.open('x', encoding='utf-8', newline='\n') as f:
        json.dump(value, f, indent=2, ensure_ascii=False); f.write('\n'); f.flush(); os.fsync(f.fileno())
    os.replace(temp, path)

def publish(path, value):
    """Atomic, exclusive publication; existing different bytes are a conflict."""
    path = Path(path); path.parent.mkdir(parents=True, exist_ok=True)
    data = value if isinstance(value, bytes) else (json.dumps(value, indent=2, ensure_ascii=False)+'\n').encode('utf-8')
    if path.exists():
        if path.read_bytes() != data:
            raise Pending('Conflicting existing artifact: ' + str(path))
        return
    temp = path.with_name(path.name+'.'+uuid.uuid4().hex+'.partial')
    with temp.open('xb') as f:
        f.write(data); f.flush(); os.fsync(f.fileno())
    try:
        os.link(temp, path)
    except FileExistsError:
        if path.read_bytes() != data: raise Pending('Concurrent artifact conflict: '+str(path))
    # Deliberately retain the completed partial; no earlier evidence is removed.

def verified(path, expected, size=None):
    path = Path(path)
    if not path.is_file() or (size is not None and path.stat().st_size != size) or sha(path) != expected:
        raise Pending('Hash/size mismatch; preserved for inspection: '+str(path))

def contained(root, relative):
    """Resolve existing links as well as '..' before any local copy/publication."""
    root = Path(root).resolve(); path = root / relative
    if not path.resolve().is_relative_to(root):
        raise Pending('File path escapes its approved directory.')
    return path

def call(args, timeout=3600, capture=True):
    result = subprocess.run([str(x) for x in args], cwd=ROOT, capture_output=capture,
                            text=True, encoding='utf-8', errors='replace', timeout=timeout)
    if result.returncode:
        # Do not print untrusted cloud stderr, which may contain OAuth/session URLs.
        raise Pending(f'{Path(str(args[0])).name} failed (exit {result.returncode}). Existing files retained.')
    return result.stdout if capture else ''

class Lock:
    """OS lock: automatically released after crashes, no stale PID deletion."""
    def __init__(self, path): self.path=Path(path); self.file=None
    def __enter__(self):
        self.path.parent.mkdir(parents=True,exist_ok=True)
        self.file=self.path.open('a+b'); self.file.seek(0)
        if self.path.stat().st_size==0: self.file.write(b'0'); self.file.flush()
        self.file.seek(0)
        try:
            if os.name=='nt':
                import msvcrt
                msvcrt.locking(self.file.fileno(),msvcrt.LK_NBLCK,1)
            else:
                import fcntl
                fcntl.flock(self.file.fileno(),fcntl.LOCK_EX|fcntl.LOCK_NB)
        except OSError:
            self.file.close(); self.file=None
            raise Pending('Another backup is running. Let it finish, then rerun.')
        return self
    def __exit__(self,*_):
        if self.file: self.file.close()

def config():
    runtime=Path.home()/'.cache/codex-runtimes/codex-primary-runtime/dependencies'
    venv=ROOT/'.tools/backup-venv/Scripts/python.exe'
    c={'schema_version':1,'serial':'XOVSTSHYNREMZ5D6','phone_export_directory':'/sdcard/Download/F8 bedankt onwijs van mijzelf',
       'python':str(venv if venv.exists() else runtime/'python/python.exe'),
       'asr_python':str(ROOT/'.tools/asr-venv/Scripts/python.exe'),
       'small_model':str(ROOT/'.tools/asr-models/faster-whisper-small'),
       'medium_model':str(ROOT/'.tools/asr-models/faster-whisper-medium'),
       'adb':str(ROOT/'.tools/android-sdk/platform-tools/adb.exe'),
       'pdftoppm':str(runtime/'native/poppler/Library/bin/pdftoppm.exe'),
       'rclone':str(ROOT/'.tools/rclone/rclone-v1.75.1-windows-amd64/rclone.exe'),
       'rclone_config':str(Path(os.getenv('LOCALAPPDATA',Path.home()))/'PrivateRecorderBackup/rclone.conf'),
       'google_remote':'recorder-google:','google_root_id':'1Pr4bOvW-aizD9upFl8gpEA9CsH1l2GUG',
       'onedrive_remote':'recorder-onedrive:Private Recorder Evidence',
       'onedrive_local':str(Path.home()/'OneDrive/Private Recorder Evidence'),
       'phone_reserve_bytes':20*1024**3,'minimum_export_interval_seconds':3600,'threads':8}
    p=ROOT/'recorder-backup.local.json'
    if p.exists(): c.update(read(p))
    if c['phone_reserve_bytes']<20*1024**3: raise Pending('Phone reserve must be at least 20 GiB.')
    if c['minimum_export_interval_seconds']<3600: raise Pending('Automatic full exports must remain at least one hour apart.')
    return c

def latest_prior(evidence=EVIDENCE):
    candidates=[]
    for p in evidence.glob('20??-??-??-??????/individual-files.filelist.json'):
        receipt=p.parent/'source-transfer-receipt.json'
        if not receipt.exists(): continue
        r=read(receipt); f=read(p)
        manifest_hash=f.get('original_archive_sha256')
        legacy_verified=(r.get('status')=='phone_and_laptop_sha256_verified'
                         and r.get('laptop_sha256')==manifest_hash)
        post_pull_verified=(r.get('status')=='phone_post_and_laptop_sha256_verified'
                            and r.get('phone_sha256_before_pull')==manifest_hash
                            and r.get('phone_sha256_after_pull')==manifest_hash
                            and r.get('laptop_sha256')==manifest_hash)
        if manifest_hash and (legacy_verified or post_pull_verified):
            candidates.append((r.get('snapshot_export_epoch_ms',0),p))
    if not candidates: raise Pending('No authoritative prior file list found. Initial backup needs review.')
    return max(candidates)[1]
