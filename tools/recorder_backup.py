"""One-click standalone Private Recorder backups. Run from Recorder Backup.cmd."""
import argparse
import json
import os
import re
import subprocess
import sys
import uuid
from pathlib import Path
from datetime import datetime,timezone,timedelta
from recorder_backup_common import ROOT,EVIDENCE,Pending,Lock,read,sha,publish,atomic,utc,config,latest_prior,call
from recorder_backup_phone import Phone,NAME,select_source,copy_source
from recorder_backup_pipeline import process
from recorder_backup_cloud import distribute

STATE=EVIDENCE/'.standalone'

def preflight(c):
    for key in ('python','asr_python','adb','pdftoppm'):
        if not Path(c[key]).is_file():raise Pending('Required local tool missing: '+key+'. See docs/STANDALONE_BACKUP.md.')
    pins=read(ROOT/'tools/recorder-model-pins.json')
    for model in ('small','medium'):
        for name,expected in pins[model].items():
            p=Path(c[model+'_model'])/name
            if not p.is_file() or sha(p)!=expected:raise Pending('Pinned local model changed/missing: '+model+'/'+name)
    call([c['python'],'-c','import reportlab,pypdf,pdfplumber,PIL'],timeout=30)
    call([c['asr_python'],'-c','import faster_whisper'],timeout=60)

def status(c):
    prior=latest_prior(); f=read(prior)
    jobs=[]
    for p in EVIDENCE.glob('20??-??-??-??????/standalone-job.json'):
        r=read(p)
        if r.get('status') not in ('complete','source_verified_no_new_recordings'):jobs.append({'batch':p.parent.name,'status':r.get('status')})
    result={'latest_source':prior.parent.name,'completed_recordings':f['snapshot_segment_count'],
            'pending_standalone_jobs':jobs,'cloud_sign_in_config_present':Path(c['rclone_config']).exists(),
            'phone_actions_performed':False,'instructions':'Double-click Recorder Backup.cmd with phone connected, unlocked and Recorder visible. Run Recorder Cloud Setup.cmd once for direct cloud authentication.'}
    print(json.dumps(result,indent=2));return 0

def setup_cloud(c):
    if not Path(c['rclone']).is_file():raise Pending('Rclone missing. Follow docs/STANDALONE_BACKUP.md to install the pinned official release.')
    conf=Path(c['rclone_config']);conf.parent.mkdir(parents=True,exist_ok=True)
    # Windows user-only folder: secrets never enter source control or evidence uploads.
    if os.name=='nt':
        identity=call(['whoami']).strip()
        call(['icacls',str(conf.parent),'/inheritance:r','/grant:r',identity+':(OI)(CI)F','SYSTEM:(OI)(CI)F'])
    print('One-time standalone cloud setup. No Codex account is used.\n')
    print('Create two remotes in the following rclone wizard:')
    print('  recorder-google   -> Google Drive (drive), your evidence account.')
    print('  recorder-onedrive -> Microsoft OneDrive (onedrive), your existing personal account.')
    print('Google needs your own Desktop OAuth client if the shared rclone client is unavailable. Instructions: https://rclone.org/drive/#making-your-own-client-id')
    print('Use Drive access that can see the existing evidence folder; drive.file alone cannot see earlier uploads.')
    print('Google destination is pinned to the existing evidence folder ID; OneDrive uses Private Recorder Evidence.')
    print('Sign in and review provider permissions yourself. No public links or permission changes are made by the backup tool.')
    print('Credentials stay in: '+str(conf)+'\nQuit the wizard when both remotes are saved.\n',flush=True)
    code=subprocess.call([c['rclone'],'--config',str(conf),'config'])
    if code:raise Pending('Cloud setup did not finish. Existing evidence is unchanged.')
    from recorder_backup_cloud import Cloud
    for provider in ('google','onedrive'):
        Cloud(c,provider)
        print(provider+': connected; destination private permissions verified.')
    print('Cloud setup complete. Double-click Recorder Backup.cmd to resume pending uploads.')
    return 0

def update_queue(batch,result,job):
    path=EVIDENCE/'pending-phone-backup.json'
    if not path.exists():return
    q=read(path); sr=read(batch/'source-transfer-receipt.json')
    cutoff=datetime.fromtimestamp(sr['snapshot_export_epoch_ms']/1000,timezone.utc)
    q.update(updated_at_utc=utc(),latest_verified_source_snapshot=batch.name,
             latest_verified_source_filelist=str(batch/'individual-files.filelist.json'),
             latest_source_cutoff_utc=cutoff.isoformat(),latest_verified_source_cutoff_utc=cutoff.isoformat(),
             last_full_export_cutoff_utc=cutoff.isoformat(),laptop_verified_recordings=sr['exported_recordings'],
             next_automatic_full_export_not_before_utc=(cutoff+timedelta(hours=1)).isoformat(),
             standalone_latest_job=str(batch/'standalone-job.json'),standalone_latest_result=str(batch/'standalone-result.json'))
    if job['status'] in ('complete','source_verified_no_new_recordings'):q.update(current_processing_snapshot=None,current_processing_status=job['status'])
    else:q.update(current_processing_snapshot=batch.name,current_processing_status=job['status'])
    # Existing provider hold fields and earlier source requests are intentionally preserved.
    atomic(path,q)

def finish_job(batch,c):
    jp=batch/'standalone-job.json';job=read(jp)
    if not (batch/'source-transfer-receipt.json').exists():
        phone=Phone(c);phone.connected()
        copy_source(phone,job['phone_path'],batch,job['source_was_already_completed'],job)
    result=process(batch,Path(job['prior_filelist']),c)
    result['cutoff_utc']=datetime.fromtimestamp(result['cutoff_epoch_ms']/1000,timezone.utc).isoformat()
    if result['new_recordings']:
        print('Local PDF and archive verified. Uploading/checking the two private cloud destinations...',flush=True)
        cloud=distribute(batch,c);result['cloud']=cloud
        complete=all(cloud.get(label+'_'+provider,{}).get('status')=='complete_remote_sha256_verified'
                     for label in ('originals','artifacts') for provider in ('google','onedrive'))
        result['status']='complete' if complete else 'local_complete_cloud_pending'
    result['checked_at_utc']=utc();result['source_request_consumed']=True
    atomic(batch/'standalone-result.json',result)
    job.update(status=result['status'],source_request_consumed=True,updated_at_utc=utc())
    atomic(jp,job);update_queue(batch,result,job)
    print(json.dumps(result,indent=2,ensure_ascii=False))
    return 0 if result['status'] in ('complete','source_verified_no_new_recordings') else 2

def run(c,args):
    STATE.mkdir(parents=True,exist_ok=True)
    with Lock(STATE/'backup.lock'):
        # Resume own durable work first. Frozen agent-created collections are never rebuilt.
        pending=[p for p in sorted(EVIDENCE.glob('20??-??-??-??????/standalone-job.json'))
                 if read(p).get('status') not in ('complete','source_verified_no_new_recordings')]
        if pending:
            preflight(c)
            for p in pending:
                code=finish_job(p.parent,c)
                if code:return code
            return 0
        if args.resume:return status(c)
        queue=read(EVIDENCE/'pending-phone-backup.json') if (EVIDENCE/'pending-phone-backup.json').exists() else {}
        busy=queue.get('current_processing_snapshot')
        if busy:
            raise Pending('The existing backup coordinator is still processing '+str(busy)+'. Resume that batch before collecting another.')
        preflight(c);prior=latest_prior();phone=Phone(c);phone.connected()
        intent_path=STATE/'source-intent.json'
        intent=read(intent_path) if intent_path.exists() else {}
        if not intent or intent.get('consumed'):
            intent={'id':uuid.uuid4().hex,'created_at_utc':utc(),'timestamp_basis':'Standalone launcher invocation observation',
                    'scheduled':args.scheduled,'consumed':False};atomic(intent_path,intent)
        path,existing=select_source(phone,prior,args.scheduled,intent,intent_path)
        if path is None:
            intent.update(consumed=True,completed_at_utc=utc(),outcome='no_new_finished_recordings');atomic(intent_path,intent)
            print('No new finished recordings. No export or duplicate upload created.');return 0
        m=NAME.fullmatch(Path(path).name)
        name=m[1][:4]+'-'+m[1][4:6]+'-'+m[1][6:]+'-'+m[2];batch=EVIDENCE/name
        batch.mkdir(exist_ok=True);(batch/'working').mkdir(exist_ok=True)
        if (batch/'distribution-status-summary.json').exists():raise Pending('This collection is already frozen; reconcile its existing receipts.')
        job={'schema_version':1,'status':'source_pending','created_at_utc':utc(),'prior_filelist':str(prior),
             'phone_path':path,'source_was_already_completed':existing,'source_intent':intent}
        publish(batch/'standalone-job.json',job)
        # Consume the invocation once a job owns it. Restarting resumes the same job.
        intent.update(consumed=True,batch=batch.name);atomic(intent_path,intent)
        return finish_job(batch,c)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--status',action='store_true',help='Read local state only; no phone/cloud mutations')
    p.add_argument('--preflight',action='store_true',help='Validate local tools and pinned models only')
    p.add_argument('--resume',action='store_true',help='Resume standalone jobs only; never start a new export')
    p.add_argument('--scheduled',action='store_true',help='Enforce automatic hourly gate; default is an explicit manual pull')
    p.add_argument('--setup-cloud',action='store_true',help='Interactive one-time cloud sign-in wizard')
    args=p.parse_args()
    try:
        c=config()
        if args.setup_cloud:return setup_cloud(c)
        if args.status:return status(c)
        if args.preflight:preflight(c);print('Local tools and pinned models verified.');return 0
        return run(c,args)
    except Pending as e:
        print('\nPENDING: '+str(e),file=sys.stderr);return 2
    except KeyboardInterrupt:
        print('\nInterrupted. Retained files/checkpoints will be checked on the next run.',file=sys.stderr);return 130
    except Exception as e:
        # Avoid displaying raw network/credential errors. Preserve local diagnostic class.
        print('\nFAILED: '+type(e).__name__+'. Existing files retained. See evidence/.standalone/last-error.json.',file=sys.stderr)
        atomic(STATE/'last-error.json',{'at_utc':utc(),'error_type':type(e).__name__,'reason':'Unexpected failure; resume after inspecting local inputs. No remote credentials or raw transport output are logged.'})
        return 1

if __name__=='__main__':raise SystemExit(main())
