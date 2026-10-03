"""USB-only normal Recorder export and append-only, verified source copying."""
import hashlib
import os
import re
import shlex
import shutil
import struct
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from datetime import datetime, timezone
from recorder_backup_common import ROOT,EVIDENCE,Pending,read,sha,publish,atomic,utc,verified

NAME=re.compile(r'PrivateRecorder_(\d{8})_(\d{6})Z\.zip\Z')

def parse_ui(raw):
    root=ET.fromstring(raw); nodes=list(root.iter('node'))
    texts=[n.get('text','') for n in nodes]; joined='\n'.join(texts)
    match=re.search(r'(\d+) finished segments?',joined)
    return {'nodes':nodes,'texts':texts,'text':joined,'finished':int(match[1]) if match else None,
            'recorder':any(n.get('package')=='nl.privaterecorder' for n in nodes),
            'active':'Recording' in texts,'exporting':any(t.startswith('Exporting ') for t in texts)}

def eocd_complete(tail,size):
    i=tail.rfind(b'PK\x05\x06')
    if i<0 or len(tail)-i<22: return False
    _,disk,start_disk,a,b,length,offset,comment=struct.unpack_from('<4s4H2LH',tail,i)
    return disk==start_disk==0 and a==b and i+22+comment==len(tail) and offset+length+22+comment==size

def reserve_ok(phone_free,laptop_free,estimate,reserve):
    return phone_free-estimate>=reserve and laptop_free>=estimate*6+2*1024**3

class Phone:
    def __init__(self,c): self.c=c;self.last_ui=None
    def adb(self,*args,binary=False,timeout=45):
        p=subprocess.run([self.c['adb'],'-s',self.c['serial'],*args],capture_output=True,timeout=timeout)
        if p.returncode: raise Pending('USB operation unavailable; reconnect/unlock the authorized phone and rerun.')
        return p.stdout if binary else p.stdout.decode('utf-8',errors='replace').strip()
    def connected(self):
        # This does not pair, enable debugging, restart adb, or connect over a network.
        if self.adb('get-state')!='device': raise Pending('Authorized USB connection unavailable.')
    def shell(self,command,**kwargs): return self.adb('shell',command,**kwargs)
    def stat(self,path):
        parts=self.shell("stat -c '%s %Y' "+shlex.quote(path)).split()
        if len(parts)!=2 or not all(x.isdigit() for x in parts): raise Pending('Cannot inspect export size/time.')
        return tuple(map(int,parts))
    def ui(self):
        self.last_ui=None
        window=self.shell('dumpsys window',timeout=30)
        if re.search(r'(?:mShowingLockscreen|isStatusBarKeyguard|mDreamingLockscreen)=true',window):
            raise Pending('Unlock the phone and leave Private Recorder visible. Capture remains untouched.')
        path='/sdcard/recorder-backup-ui-'+uuid.uuid4().hex+'.xml'
        result=self.shell('uiautomator dump '+shlex.quote(path),timeout=30)
        if 'UI hierchary dumped to:' not in result and 'UI hierarchy dumped to:' not in result:
            raise Pending('Fresh recorder screen inspection failed; no cached UI will be used.')
        raw=self.shell('cat '+shlex.quote(path)); state=parse_ui(raw)
        self.last_ui=(time.monotonic(),state)
        return state
    def tap(self,state,text):
        if self.last_ui is None or self.last_ui[1] is not state or time.monotonic()-self.last_ui[0]>5:
            raise Pending('Screen changed or expired; rerun to inspect it again.')
        # Only these two normal export controls can ever be pressed.
        if text not in ('Export evidence snapshot','Export evidence ZIP','Save','SAVE','Opslaan'):
            raise Pending('Unsupported phone action')
        nodes=[n for n in state['nodes'] if n.get('text')==text and n.get('enabled')=='true' and n.get('clickable')=='true']
        if len(nodes)!=1: raise Pending('Expected export control is not uniquely available.')
        xy=list(map(int,re.findall(r'\d+',nodes[0].get('bounds',''))))
        if len(xy)!=4 or xy[2]<=xy[0] or xy[3]<=xy[1]: raise Pending('Invalid export control bounds.')
        self.adb('shell','input','tap',str((xy[0]+xy[2])//2),str((xy[1]+xy[3])//2))
        self.last_ui=None
    def exports(self):
        raw=self.shell('ls -1 '+shlex.quote(self.c['phone_export_directory']))
        return sorted(x for x in raw.splitlines() if NAME.fullmatch(x))
    def completed(self,path):
        before=self.stat(path)
        tail=self.adb('exec-out','tail -c 65557 '+shlex.quote(path),binary=True)
        return before if eocd_complete(tail,before[0]) and before==self.stat(path) else None
    def free(self):
        lines=self.shell('df -k /sdcard').splitlines()
        try: return int(lines[-1].split()[3])*1024
        except (ValueError,IndexError): raise Pending('Phone free space could not be verified.')
    def sha(self,path):
        h=self.shell('sha256sum '+shlex.quote(path),timeout=300).split()[0]
        if not re.fullmatch('[0-9a-f]{64}',h): raise Pending('Invalid phone hash response.')
        return h

def select_source(phone,prior,scheduled,run_state,state_path):
    names=phone.exports(); baseline=read(prior)['original_archive_name']
    newer=[n for n in names if n>baseline]
    if newer:
        name=newer[-1]; path=phone.c['phone_export_directory']+'/'+name
        if not phone.completed(path): raise Pending('Newest phone export is still writing or incomplete. It is retained; rerun later.')
        return path,True
    if run_state.get('export_clicked'):
        # Never repeat a click after an interrupted export attempt.
        state=phone.ui()
        if state['recorder']: raise Pending('The previous export has not appeared. Check its result on the phone; no second export was started.')
        finish_picker(phone,state,run_state,state_path)
        return wait_export(phone,names),False
    state=phone.ui()
    if not state['recorder'] or state['finished'] is None:
        raise Pending('Open Private Recorder on the unlocked phone. The tool does not launch, stop or reconfigure it.')
    if state['exporting']: raise Pending('Recorder export is still writing; rerun when complete.')
    if state['finished']<=read(prior)['snapshot_segment_count']: return None,False
    receipt=read(prior.parent/'source-transfer-receipt.json')
    cutoff=receipt['snapshot_export_epoch_ms']/1000
    if scheduled and time.time()<cutoff+phone.c['minimum_export_interval_seconds']:
        raise Pending('Automatic hourly export limit has not elapsed.')
    # UI size is rounded: round up generously and include metadata growth.
    m=re.search(r'([\d.]+)\s*(GiB|MiB|KiB|B) saved',state['text'])
    if not m: raise Pending('Could not estimate the full snapshot size from the current Recorder screen.')
    units={'GiB':1024**3,'MiB':1024**2,'KiB':1024,'B':1}
    estimate=max(int((float(m[1])+0.1)*units[m[2]]*1.25),int(receipt['size']*state['finished']/read(prior)['snapshot_segment_count']*1.25))
    phone_free=phone.free();laptop_free=shutil.disk_usage(EVIDENCE).free
    if not reserve_ok(phone_free,laptop_free,estimate,phone.c['phone_reserve_bytes']):
        raise Pending('Space reserve prevents a full export. Arrange a recording break for the already-built incremental-export update; earlier exports are retained.')
    # Space inspection may take time. Reinspect and derive the button again.
    state=phone.ui()
    if not state['recorder'] or state['exporting'] or state['finished'] is None:
        raise Pending('Recorder screen changed during preflight.')
    text=next((x for x in ('Export evidence snapshot','Export evidence ZIP') if x in state['texts']),None)
    if not text: raise Pending('Supported normal export button not visible.')
    run_state.update(export_clicked=True,export_click_intent_utc=utc(),phone_free_before_export=phone_free,
                     laptop_free_before_export=laptop_free,estimated_export_bytes=estimate,recording_active=state['active'])
    atomic(state_path,run_state)  # Intent before effect prevents duplicate exports after a crash.
    phone.tap(state,text)
    state=phone.ui();finish_picker(phone,state,run_state,state_path)
    return wait_export(phone,names),False

def finish_picker(phone,state,run_state,state_path):
    if state['recorder']: return
    if not any(n.get('package','').endswith('documentsui') for n in state['nodes']):
        raise Pending('Complete the normal Save dialog on the phone, then rerun.')
    folder=Path(phone.c['phone_export_directory']).name
    filename=next((t for t in state['texts'] if NAME.fullmatch(t)),None)
    if folder not in state['texts'] or not filename:
        raise Pending('In the phone Save dialog, choose the existing export folder and save without replacing an earlier ZIP; then rerun.')
    if filename in phone.exports(): raise Pending('Save filename already exists; earlier export will not be overwritten.')
    # Refresh after listing; never reuse old coordinates.
    state=phone.ui()
    if folder not in state['texts'] or filename not in state['texts']: raise Pending('Save dialog changed.')
    save=next((t for t in ('Save','SAVE','Opslaan') if t in state['texts']),None)
    if not save: raise Pending('Use the phone Save control, then rerun.')
    phone.tap(state,save);run_state['saved_filename']=filename;atomic(state_path,run_state)

def wait_export(phone,previous):
    deadline=time.monotonic()+600
    while time.monotonic()<deadline:
        names=[n for n in phone.exports() if n not in previous]
        if names:
            p=phone.c['phone_export_directory']+'/'+names[-1]
            if phone.completed(p): return p
        print('Waiting for the normal phone export to finish...',flush=True);time.sleep(10)
    raise Pending('Export is still pending. Rerun later; the tool will not create another snapshot.')

def copy_source(phone,path,batch,existing,run_state):
    receipt_path=batch/'source-transfer-receipt.json'
    if receipt_path.exists():
        r=read(receipt_path);verified(r['laptop_path'],r['laptop_sha256'],r['size']);return r
    stat=phone.completed(path)
    if not stat: raise Pending('Export is not demonstrably complete.')
    size,mtime=stat
    if shutil.disk_usage(EVIDENCE).free<size*6+2*1024**3: raise Pending('Not enough laptop space for source, derivatives and archive.')
    before=phone.sha(path)
    if phone.stat(path)!=stat: raise Pending('Phone export changed before copying.')
    dest=batch/'original-export'/Path(path).name;dest.parent.mkdir(parents=True,exist_ok=True)
    pre=batch/'standalone-source-precheck.json'
    if pre.exists():
        old=read(pre)
        if old['sha256']!=before or old['size']!=size: raise Pending('Export changed since previous copy; retained partials are untouched.')
    else: publish(pre,{'phone_path':path,'sha256':before,'size':size,'phone_stat':stat,'checked_at_utc':utc()})
    partials=sorted(dest.parent.glob(dest.name+'.partial-*')); completed=next((p for p in partials if p.stat().st_size==size and sha(p)==before),None)
    if dest.exists(): verified(dest,before,size);completed=dest
    if completed is None:
        partial=dest.with_name(dest.name+'.partial-'+uuid.uuid4().hex)
        print('Copying completed source ZIP from USB...',flush=True)
        phone.adb('pull','-a',path,str(partial),timeout=1800)
        verified(partial,before,size);completed=partial
    local_hash=sha(completed)
    after=phone.sha(path)
    if before!=after or local_hash!=before or phone.stat(path)!=stat: raise Pending('Source changed during copy; retained laptop copy is not published.')
    from verify_export import verify_export
    verification=verify_export(completed)
    with zipfile.ZipFile(completed) as z:
        if z.testzip() is not None: raise Pending('ZIP CRC failure')
        import json
        metadata=json.loads(z.read('export_metadata.json'))
    if not dest.exists(): os.link(completed,dest)
    r={'schema_version':1,'status':'phone_and_laptop_sha256_verified','verified_at_utc':utc(),
       'phone_serial':phone.c['serial'],'phone_path':path,'laptop_path':str(dest),'size':size,
       'phone_sha256':before,'phone_sha256_before_pull':before,'phone_sha256_after_pull':after,'laptop_sha256':local_hash,
       'zip_crc_all_members':'passed','snapshot_export_epoch_ms':metadata['export_epoch_ms'],
       'exported_recordings':metadata['finalized_segments'],'export_metadata':metadata,
       'source_was_already_completed':existing,'new_export_created':not existing,
       'retained_completed_partial_path':str(completed),'completion_basis':'Stable phone stat and complete ZIP central directory, phone pre/post SHA-256, full local ZIP/member verification.',
       'safe_to_unplug_after_this_verification':True,'active_recording_untouched':True,'preflight':run_state}
    publish(receipt_path,r)
    print('Source verified. Safe to unplug; processing the laptop copy.',flush=True)
    return r
