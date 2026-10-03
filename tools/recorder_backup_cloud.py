"""Standalone rclone transport with private-folder checks and full SHA-256 readbacks."""
import hashlib
import json
import os
import shutil
import subprocess
import threading
import uuid
from pathlib import Path,PurePosixPath
from recorder_backup_common import ROOT,EVIDENCE,Pending,read,sha,verified,atomic,utc,call,contained

def relative_safe(name):
    p=PurePosixPath(name)
    if not name or p.is_absolute() or '..' in p.parts or ':' in name or '\\' in name or '\n' in name or '\r' in name:
        raise Pending('Unsafe relative transfer path.')
    return p

def private_metadata(item,provider):
    m=item.get('Metadata',{})
    if 'permissions' not in m:
        raise Pending('Provider did not return permissions metadata. Private status is unconfirmed; no upload performed.')
    permissions=m['permissions']
    if isinstance(permissions,str): permissions=json.loads(permissions)
    if not isinstance(permissions,list): raise Pending('Unexpected permissions response.')
    for permission in permissions:
        owner=permission.get('role')=='owner' if provider=='google' else permission.get('roles')==['owner']
        if not owner: raise Pending('Destination has non-owner permissions; no private backup upload performed.')
    if m.get('shared-scope'): raise Pending('OneDrive destination is shared; no upload performed.')
    return True

class Cloud:
    def __init__(self,c,provider):
        self.c=c;self.provider=provider;self.root=c[provider+'_remote'];self.cache={}
        if not Path(c['rclone']).is_file() or not Path(c['rclone_config']).is_file():
            raise Pending('Run Recorder Cloud Setup.cmd once to connect Google Drive and OneDrive.')
        if not re_remote(self.root): raise Pending('Cloud destination must name a configured remote, not a local path.')
        self.args=[c['rclone'],'--config',c['rclone_config'],'--retries','1','--low-level-retries','2','--contimeout','20s','--timeout','5m']
        if provider=='google':self.args+=['--drive-root-folder-id',c['google_root_id'],'--drive-metadata-permissions','read']
        else:self.args+=['--onedrive-metadata-permissions','read']
        remotes=json.loads(call(self.args+['listremotes','--json']))
        # rclone listremotes --json has no credentials.
        expected='drive' if provider=='google' else 'onedrive'
        name=self.root.split(':',1)[0]
        if not any(r.get('name','').rstrip(':')==name and r.get('type')==expected for r in remotes):
            raise Pending(f'Configure the {name} remote as {expected} in Recorder Cloud Setup.cmd.')
        private_metadata(self.stat(''),provider)
    def path(self,rel):return self.root.rstrip('/')+('/' if not self.root.endswith(':') else '')+rel
    def run(self,*args):return call(self.args+list(args),timeout=3600)
    def stat(self,rel):return json.loads(self.run('lsjson',self.path(rel),'--stat','--metadata'))
    def listing(self,rel):
        if rel not in self.cache:self.cache[rel]=json.loads(self.run('lsjson',self.path(rel),'--metadata'))
        return self.cache[rel]
    def child(self,parent,name):
        matches=[i for i in self.listing(parent) if i['Name']==name]
        if len(matches)>1:raise Pending('Duplicate cloud names need review: '+name)
        return matches[0] if matches else None
    def ensure_directory(self,rel):
        parent=''
        for part in relative_safe(rel).parts:
            child=self.child(parent,part);current='/'.join(x for x in (parent,part) if x)
            if child is None:
                self.run('mkdir',self.path(current));self.cache.pop(parent,None)
                child=self.child(parent,part)
            if child is None or not child['IsDir']:raise Pending('Cloud directory conflicts with a file.')
            private_metadata(self.stat(current),self.provider);parent=current
    def hash_remote(self,rel):
        # Stream bytes straight into a hash. Never print/store temporary bearer URLs.
        proc=subprocess.Popen(self.args+['cat',self.path(rel)],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
        timer=threading.Timer(1800,proc.kill);timer.daemon=True;timer.start()
        try:
            h=hashlib.sha256();size=0
            for chunk in iter(lambda:proc.stdout.read(1024*1024),b''):
                h.update(chunk);size+=len(chunk)
            if proc.wait()!=0:raise Pending('Remote readback did not finish; previous receipts are preserved.')
            return size,h.hexdigest()
        finally:
            timer.cancel();proc.stdout.close()
            if proc.poll() is None:proc.kill();proc.wait()
    def transfer(self,local,rel,expected,size):
        verified(local,expected,size);relative_safe(rel)
        parent=str(PurePosixPath(rel).parent);parent='' if parent=='.' else parent
        if parent:self.ensure_directory(parent)
        present=self.child(parent,PurePosixPath(rel).name)
        if present:
            if present['IsDir'] or present['Size']!=size:raise Pending('Conflicting remote file; existing bytes retained: '+rel)
            private_metadata(self.stat(rel),self.provider)
            actual=self.hash_remote(rel)
            if actual!=(size,expected):raise Pending('Remote file differs; no overwrite: '+rel)
            disposition='existing_copy_reconciled'
        else:
            self.run('copyto',str(local),self.path(rel),'--immutable','--checksum','--no-update-modtime')
            self.cache.pop(parent,None)
            if self.child(parent,PurePosixPath(rel).name) is None:raise Pending('Upload did not appear.')
            private_metadata(self.stat(rel),self.provider)
            if self.hash_remote(rel)!=(size,expected):raise Pending('Uploaded remote bytes do not match source.')
            disposition='uploaded_and_remote_hash_verified'
        verified(local,expected,size)
        return {'relative_path':rel,'size':size,'sha256':expected,'remote_sha256_verified':True,
                'private_permissions_verified':True,'checked_at_utc':utc(),'disposition':disposition}

def re_remote(name):
    import re
    return bool(re.fullmatch(r'[A-Za-z][A-Za-z0-9_-]+:[^\r\n]*',name))

def transfer_scope(scope,c,provider,prefix=''):
    mp=scope/'individual-files.filelist.json';m=read(mp)
    rp=scope/(provider+'-standalone-readbacks.json')
    state=read(rp) if rp.exists() else {'manifest_sha256':sha(mp),'files':{},'status':'pending'}
    if state['manifest_sha256']!=sha(mp):raise Pending('Transfer manifest changed.')
    # Connector-confirmed Google receipts are retained and never uploaded/read back again.
    legacy={}
    lp=scope/'google-drive-readback.jsonl'
    if provider=='google' and lp.exists():
        legacy={r['relative_path']:r for r in (json.loads(x) for x in lp.read_text().splitlines() if x.strip()) if r.get('verified')}
    cloud=None
    for n,f in enumerate(m['files'],1):
        rel=f['relative_path'];relative_safe(rel);src=Path(m['output_directory'])/rel
        if not src.resolve().is_relative_to(Path(m['output_directory']).resolve()):raise Pending('Transfer source escapes manifest root.')
        verified(src,f['sha256'],f['size'])
        done=state['files'].get(rel)
        if done and not (done.get('remote_sha256_verified') and done.get('private_permissions_verified')):done=None
        done=done or legacy.get(rel)
        if done and done.get('sha256')==f['sha256'] and done.get('size')==f['size']:continue
        if cloud is None:cloud=Cloud(c,provider)
        dest='/'.join(x for x in (prefix,rel) if x)
        receipt=cloud.transfer(src,dest,f['sha256'],f['size']);state['files'][rel]=receipt
        atomic(rp,state);print(f'{provider}: {n}/{len(m["files"])} verified',flush=True)
    state.update(status='complete_remote_sha256_verified',expected_files=len(m['files']),completed_utc=utc(),
                 reused_connector_verified_files=len(legacy),remote_root=c[provider+'_remote'])
    atomic(rp,state);return {'status':state['status'],'files':len(m['files'])}

def local_onedrive(scope,c,prefix=''):
    m=read(scope/'individual-files.filelist.json');root=Path(c['onedrive_local'])
    if not root.exists():raise Pending('Existing OneDrive sync folder unavailable.')
    copies=[]
    for f in m['files']:
        rel='/'.join(x for x in (prefix,f['relative_path']) if x);relative_safe(rel)
        relative_safe(f['relative_path'])
        source=contained(m['output_directory'],f['relative_path']);verified(source,f['sha256'],f['size'])
        dest=contained(root,rel)
        if dest.exists():verified(dest,f['sha256'],f['size'])
        else:
            dest.parent.mkdir(parents=True,exist_ok=True)
            # Stage on the same drive outside OneDrive, preserving source metadata.
            stage=EVIDENCE/'.standalone/sync-stage';stage.mkdir(parents=True,exist_ok=True)
            temp=stage/uuid.uuid4().hex;shutil.copy2(source,temp);verified(temp,f['sha256'],f['size'])
            try:os.link(temp,dest)
            except OSError as e:raise Pending('Cannot exclusively publish OneDrive local copy on this volume.') from e
            verified(dest,f['sha256'],f['size'])
        copies.append({'relative_path':rel,'size':f['size'],'sha256':f['sha256'],'destination':str(dest)})
    rp=scope/'onedrive-standalone-local.json'
    atomic(rp,{'status':'local_sync_copy_verified_remote_bytes_not_implied','manifest_sha256':sha(scope/'individual-files.filelist.json'),
               'files':copies,'checked_at_utc':utc(),'remote_hash_verified':False})
    return {'local_files':len(copies),'remote_hash_verified':False}

def distribute(batch,c):
    prefix=batch.name[:10]+' additions '+batch.name[-6:]+'Z';result={}
    for scope,label,p in [(batch,'originals',prefix),(batch/'artifact-transfers','artifacts','')]:
        if not (scope/'individual-files.filelist.json').exists():continue
        for provider in ('onedrive_local','google','onedrive'):
            try:result[label+'_'+provider]=local_onedrive(scope,c,p) if provider=='onedrive_local' else transfer_scope(scope,c,provider,p)
            except (Pending,subprocess.TimeoutExpired) as e:result[label+'_'+provider]={'status':'pending','reason':str(e) if isinstance(e,Pending) else 'Cloud operation timed out.'}
    return result
