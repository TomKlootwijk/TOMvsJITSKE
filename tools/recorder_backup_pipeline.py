"""Local resumable processing. Originals are never transformed or overwritten."""
import csv
import hashlib
import json
import os
import re
import shutil
import uuid
import zipfile
from collections import Counter
from pathlib import Path
from recorder_backup_common import ROOT,EVIDENCE,Pending,read,sha,publish,atomic,utc,verified,call,contained

def rows_for(batch):
    with (batch/'individual-files/INDEX.csv').open(encoding='utf-8-sig',newline='') as f:
        return sorted(csv.DictReader(f),key=lambda r:int(r['start_epoch_ms']))

def new_manifest(batch):
    fp=batch/'individual-files.filelist.json';fl=read(fp); files=[]
    for row in rows_for(batch):
        if row['status']!='finalized': raise Pending('Unfinished recording in new-only INDEX.')
        p=batch/'individual-files'/row['audio_relative_path'];verified(p,row['sha256'])
        files.append({'path':str(p),'sha256':row['sha256'],'source_snapshot_directory':str(batch),
                      'source_index_csv':str(batch/'individual-files/INDEX.csv'),'priority':False,
                      'user_marked':False,'user_name':None,'designation':'','plot_point':False})
    if len(files)!=fl['segment_count']: raise Pending('New recording count does not match verified file list.')
    return {'schema_version':1,'scope':'Only newly verified finalized recordings. Earlier recordings and fixed selections remain unchanged.',
            'source_filelist':str(fp),'source_filelist_sha256':sha(fp),'files':files}

def asr_path(batch,item,medium=False):
    return batch/'derived'/('medium-review' if medium else 'transcripts')/(Path(item['path']).stem+'_'+item['sha256'][:12]+'.json')

def review_candidate(raw):
    segments=raw['segments']
    if not segments: return False
    words=re.findall(r'\w+',' '.join(s['text'] for s in segments).casefold())
    repeated=max(Counter(words).values(),default=0)>max(6,len(words)*.2)
    return repeated or any('possible_repetition' in s['review_flags'] or 'low_model_log_probability' in s['review_flags'] for s in segments)

def make_catalog(batch,manifest):
    rows=rows_for(batch);records=[]; pdf_records=[]
    for number,(item,row) in enumerate(zip(manifest['files'],rows),1):
        p=asr_path(batch,item);raw=read(p)
        if raw['status']!='complete' or raw['source']['sha256']!=item['sha256'] or raw['human_verified']:
            raise Pending('Transcript source/status does not match verified input.')
        medium=asr_path(batch,item,True)
        flags=sorted({f for s in raw['segments'] for f in s['review_flags']})
        if not raw['segments']: flags.append('no_asr_speech_segments_not_proof_of_silence')
        if raw['language_probability']<.6: flags.append('uncertain_language_detection')
        if medium.exists(): flags.append('model_alternative_retained_not_automatically_preferred')
        # Conservative labels require no generative service and assert no events.
        category='No ASR segments' if not raw['segments'] else ('Low detected speech' if raw['duration_after_vad_seconds']<5 else 'Draft speech')
        summary='No segments returned; this does not establish silence.' if not raw['segments'] else 'Automatic '+str(raw['language'])+' draft. Topic, speaker identity and factual assertions have not been verified.'
        designation=str(item.get('designation') or '')
        user_marked=bool(item.get('user_marked') or designation)
        user_name=item.get('user_name')
        priority=bool(item.get('priority'))
        plot_point=bool(item.get('plot_point'))
        rec={'catalog_number':number,'source_filename':Path(item['path']).name,'source_path':item['path'],
             'source_sha256':item['sha256'],'source_index_row':row,'duration_seconds':float(row['duration_seconds']),
             'recording_start_utc':row['start_utc'],'recording_end_utc':row['end_utc'],
             'preferred_model':'faster-whisper-small','preferred_transcript_json':str(p),
             'preferred_transcript_json_sha256':sha(p),'alternative_transcript_jsons':[str(medium)] if medium.exists() else [],
             'category':category,'summary':summary,'review_flags':flags,'user_marked':user_marked,
             'user_name':user_name,'designation':designation,
             'diagnostics':{'asr_segment_count':len(raw['segments']),'detected_language':raw['language'],
                            'language_probability':raw['language_probability']},'human_verified':False}
        records.append(rec)
        pdf_records.append({'asr_json':str(p),'asr_sha256':sha(p),'index_csv':item['source_index_csv'],
                            'original_archive_path':row['original_archive_path'],'plot_point':plot_point,
                            'priority':priority,'user_marked':user_marked,'user_name':user_name,
                            'designation':designation,'category':category,'summary':summary})
    catalog={'schema_version':1,'created_utc':manifest['created_utc'],'human_verified':False,
             'scope':manifest['scope'],'recording_count':len(records),'recordings':records,
             'total_duration_seconds':sum(int(r['duration_us']) for r in rows)/1000000,
             'asr_input_manifest_sha256':sha(batch/'asr-combined-files.json'),
             'interpretation_notes':['Small drafts remain preferred automatically. Medium alternatives are retained for listening review.','No segment result establishes neither silence nor absence of speech.','Only explicit designations carried by the new-only manifest are applied; none are inferred from ASR.']}
    cp=batch/'derived/asr-catalog.json'; publish(cp,catalog)
    publish(batch/'derived/transcript-pdf-manifest.json',{'schema_version':1,'title':'Recording transcripts - '+batch.name,
            'created_utc':manifest['created_utc'],'search_terms':[],'catalog_source':str(cp),'catalog_sha256':sha(cp),'recordings':pdf_records})
    return catalog

def pdf_qa(batch,pdf,c):
    import pdfplumber
    from check_transcript_pdf import check
    manifest=batch/'derived/transcript-pdf-manifest.json'; report=batch/'pdf-text-qa.json'
    if report.exists(): verified(pdf,read(report)['pdf_sha256'])
    else:
        qa=check(manifest,pdf)
        if not qa['passed']: raise Pending('PDF text coverage/order failed.')
        publish(report,qa)
    bounds_path=batch/'pdf-layout-bounds.json'
    if not bounds_path.exists():
        violations=[]
        with pdfplumber.open(pdf) as doc:
            pages=len(doc.pages)
            for i,p in enumerate(doc.pages,1):
                for w in p.extract_words():
                    if w['x0']<0 or w['top']<0 or w['x1']>p.width or w['bottom']>p.height:
                        violations.append({'page':i,'text':w['text']})
        if violations: raise Pending('PDF contains text outside page bounds.')
        publish(bounds_path,{'passed':True,'pages':pages,'pdf_sha256':sha(pdf),'violations':violations,'checked_at_utc':utc()})
    render=batch/'working/pdf-render';render.mkdir(parents=True,exist_ok=True)
    count=read(report)['pages']; images=list(render.glob('page-*.png'))
    if len(images)!=count:
        call([c['pdftoppm'],'-png','-scale-to','1300',pdf,render/'page'],timeout=600)
    from PIL import Image,ImageStat
    images=sorted(render.glob('page-*.png'))
    if len(images)!=count: raise Pending('Not all PDF pages rendered.')
    for p in images:
        with Image.open(p) as im:
            if min(im.size)<500 or max(ImageStat.Stat(im.convert('L')).stddev)<1:
                raise Pending('Blank or invalid PDF page render.')
    visual=batch/'pdf-render-check.json'
    if not visual.exists():
        publish(visual,{'passed_automated_render_checks':True,'pdf_sha256':sha(pdf),'pages':count,
                        'human_visual_review_performed':False,'note':'Every page rendered and passed nonblank/dimension checks. Text/order/bounds are checked separately. These checks are not human visual review.'})

def archive_members(manifest):
    files=manifest['files']; package={k:v for k,v in manifest.items() if k!='files'}
    package['files']=[{k:v for k,v in f.items() if k!='path'} for f in files]
    data=(json.dumps(package,ensure_ascii=False,indent=2)+'\n').encode()
    expected={f['archive_path']:(f['size'],f['sha256']) for f in files}
    expected['PACKAGE_MANIFEST.json']=(len(data),hashlib.sha256(data).hexdigest())
    sums=''.join(h+'  '+n+'\n' for n,(_,h) in sorted(expected.items())).encode()
    expected['SHA256SUMS']=(len(sums),hashlib.sha256(sums).hexdigest())
    return expected,data,sums

def verify_package(path,manifest):
    expected,_,_=archive_members(manifest)
    with zipfile.ZipFile(path) as z:
        if len(z.namelist())!=len(expected) or set(z.namelist())!=set(expected): raise Pending('Archive coverage mismatch.')
        for name,(size,h) in expected.items():
            with z.open(name) as stream: actual=hashlib.file_digest(stream,'sha256').hexdigest()
            if z.getinfo(name).file_size!=size or h!=actual: raise Pending('Archive member hash mismatch: '+name)
    return len(expected)

def build_package(batch):
    manifest_path=batch/'archive-input-manifest.json'
    if not manifest_path.exists():
        fl=read(batch/'individual-files.filelist.json')
        sources=[Path(fl['output_directory'])/f['relative_path'] for f in fl['files']]
        sources+=sorted(p for p in (batch/'derived').rglob('*') if p.is_file() and '.partial' not in p.name)
        sources+=[batch/n for n in ('source-transfer-receipt.json','individual-files.filelist.json','asr-combined-files.json','pdf-text-qa.json','pdf-layout-bounds.json','pdf-render-check.json')]
        files=[{'path':str(p),'archive_path':p.relative_to(EVIDENCE).as_posix(),'size':p.stat().st_size,'sha256':sha(p)} for p in sources]
        if len({f['archive_path'] for f in files})!=len(files): raise Pending('Duplicate package member.')
        publish(manifest_path,{'schema_version':1,'created_at_utc':utc(),'human_verified_transcription':False,'files':files})
    manifest=read(manifest_path)
    for f in manifest['files']: verified(f['path'],f['sha256'],f['size'])
    archive=batch/'archives'/(batch.name[:10]+'_'+batch.name[-6:]+'Z_recordings-and-transcripts.zip')
    if not archive.exists():
        archive.parent.mkdir(exist_ok=True);part=archive.with_name(archive.name+'.partial-'+uuid.uuid4().hex)
        _,package,sums=archive_members(manifest)
        with zipfile.ZipFile(part,'x',compression=zipfile.ZIP_STORED,allowZip64=True) as z:
            for f in manifest['files']:z.write(f['path'],f['archive_path'])
            z.writestr('PACKAGE_MANIFEST.json',package);z.writestr('SHA256SUMS',sums)
        verify_package(part,manifest);os.link(part,archive)
    count=verify_package(archive,manifest)
    for f in manifest['files']:verified(f['path'],f['sha256'],f['size'])
    report=batch/'archive-verification.json'
    if not report.exists():publish(report,{'status':'all_archive_members_sha256_verified','verified_at_utc':utc(),'archive':str(archive),'archive_sha256':sha(archive),'archive_bytes':archive.stat().st_size,'file_count':count,'input_manifest_sha256':sha(manifest_path),'original_sources_rehashed_after_packaging':len(manifest['files'])})
    else:verified(archive,read(report)['archive_sha256'])
    return archive

def organize_batch(batch,source,prior,source_hash):
    fp=batch/'individual-files.filelist.json'
    if not fp.exists():
        from organize_incremental_export import organize_incremental,_verify_prior
        commit=batch/'working/organization-commit.json'
        if not commit.exists():
            organize_incremental(source,batch/'individual-files',[prior],expected_archive_sha256=source_hash,commit_path=commit)
        else:
            transaction=read(commit);report=transaction['report'];dest=batch/'individual-files'
            if report['output_directory']!=str(dest) or report['original_archive_sha256']!=source_hash:
                raise Pending('Organization recovery journal does not match source/destination.')
            stage=Path(transaction['stage_directory'])
            if not stage.resolve().is_relative_to(batch.resolve()):raise Pending('Unsafe recovery stage path.')
            present=dest if dest.exists() else stage
            for f in report['files']:verified(contained(present,f['relative_path']),f['sha256'],f['size'])
            for ref in report['reused_originals']:_verify_prior(ref)
            if present==stage:stage.rename(dest)
            publish(fp,report)
    return fp

def process(batch,prior,c):
    sr=read(batch/'source-transfer-receipt.json'); source=Path(sr['laptop_path'])
    verified(source,sr['laptop_sha256'],sr['size'])
    fp=organize_batch(batch,source,prior,sr['laptop_sha256'])
    fl=read(fp)
    for f in fl['files']:verified(Path(fl['output_directory'])/f['relative_path'],f['sha256'],f['size'])
    if fl['segment_count']==0: return {'status':'source_verified_no_new_recordings','new_recordings':0,'source_completed_recordings':fl['snapshot_segment_count'],'excluded_partials':fl['verification']['excluded_partial_segments'],'cutoff_epoch_ms':sr['snapshot_export_epoch_ms']}
    manifest_path=batch/'asr-combined-files.json'
    if not manifest_path.exists():
        m=new_manifest(batch);m['created_utc']=utc();publish(manifest_path,m)
    manifest=read(manifest_path)
    if manifest['source_filelist_sha256']!=sha(fp): raise Pending('New-only manifest changed.')
    frozen=batch/'artifact-transfers/individual-files.filelist.json'
    if frozen.exists():
        # A cloud-only resume must not generate another ASR run-summary or any derivative.
        for f in read(batch/'archive-input-manifest.json')['files']:verified(f['path'],f['sha256'],f['size'])
        for f in read(frozen)['files']:verified(Path(read(frozen)['output_directory'])/f['relative_path'],f['sha256'],f['size'])
        catalog=read(batch/'derived/asr-catalog.json');qa=read(batch/'pdf-text-qa.json');ar=read(batch/'archive-verification.json')
        verify_package(Path(ar['archive']),read(batch/'archive-input-manifest.json'))
        return {'status':'local_complete_cloud_pending','new_recordings':fl['segment_count'],'source_completed_recordings':fl['snapshot_segment_count'],
                'cutoff_epoch_ms':sr['snapshot_export_epoch_ms'],'excluded_partials':fl['verification']['excluded_partial_segments'],
                'preferred_segments':sum(r['diagnostics']['asr_segment_count'] for r in catalog['recordings']),
                'pdf':qa['pdf'],'pdf_sha256':qa['pdf_sha256'],'pdf_pages':qa['pages'],
                'archive':ar['archive'],'archive_sha256':ar['archive_sha256'],'human_visual_review_performed':False}
    print(f"Transcribing {len(manifest['files'])} new recordings locally...",flush=True)
    if not all(asr_path(batch,f).exists() and asr_path(batch,f).with_suffix('.txt').exists() for f in manifest['files']):
        call([c['asr_python'],ROOT/'tools/transcribe_local.py',manifest_path,'--model',c['small_model'],'--output',batch/'derived/transcripts','--threads',c['threads']],capture=False,timeout=86400)
    review_path=batch/'asr-repetition-review-manifest.json'
    if not review_path.exists():
        review=[f for f in manifest['files'] if review_candidate(read(asr_path(batch,f)))]
        publish(review_path,{'files':review,'purpose':'Retained alternatives for uncertain/repetitive drafts. Automatic small preference remains unchanged.'})
    if read(review_path)['files'] and not all(asr_path(batch,f,True).exists() and asr_path(batch,f,True).with_suffix('.txt').exists() for f in read(review_path)['files']):
        call([c['asr_python'],ROOT/'tools/transcribe_local.py',review_path,'--model',c['medium_model'],'--output',batch/'derived/medium-review','--threads',c['threads']],capture=False,timeout=86400)
    catalog=make_catalog(batch,manifest)
    pdf=batch/'derived'/f'Recording transcripts - {batch.name[:10]} {batch.name[-6:]}Z.pdf'
    if not pdf.exists():
        temp=pdf.with_name(pdf.stem+'.partial-'+uuid.uuid4().hex+'.pdf')
        call([c['python'],ROOT/'tools/build_transcript_pdf_unicode.py',batch/'derived/transcript-pdf-manifest.json','--output',temp],capture=False)
        from check_transcript_pdf import check
        if not check(batch/'derived/transcript-pdf-manifest.json',temp)['passed']: raise Pending('PDF validation failed.')
        os.link(temp,pdf)
        # Keep unfinished/retained work outside the derivative upload manifest.
        os.replace(temp,batch/'working'/temp.name)
    pdf_qa(batch,pdf,c)
    readme=batch/'derived/README.txt'
    if not readme.exists():publish(readme,b'AUTOMATIC TRANSCRIPTS - NOT HUMAN VERIFIED\nOriginal audio and metadata are unchanged. Categories describe draft output only.\nSmall drafts remain preferred; model alternatives are retained. No returned segments does not establish silence.\nPDF text/order/bounds and rendered pages were checked automatically. Human visual review was not performed.\n')
    archive=build_package(batch)
    index=batch/'derived/START HERE.html'
    if not index.exists():
        import html
        body=f'<!doctype html><meta charset="utf-8"><title>Recorder backup {batch.name}</title><h1>{batch.name}</h1><p>{fl["segment_count"]} new recordings. Automatic drafts, not human verified.</p><p><a href="{html.escape(pdf.name)}">Read transcript PDF</a></p><p><a href="../archives/{archive.name}">Verified archive</a></p><p>Originals: <a href="../individual-files/INDEX.csv">INDEX.csv</a>. Cloud status: <a href="../standalone-result.json">receipt</a>.</p>'
        publish(index,body.encode('utf-8'))
    scope=batch/'artifact-transfers/individual-files.filelist.json'
    if not scope.exists():
        paths=sorted(p for p in (batch/'derived').rglob('*') if p.is_file() and '.partial' not in p.name)+[archive]
        files=[{'relative_path':p.relative_to(EVIDENCE).as_posix(),'size':p.stat().st_size,'sha256':sha(p),'kind':'archive' if p==archive else 'derived'} for p in paths]
        publish(scope,{'schema_version':1,'output_directory':str(EVIDENCE),'output_file_count':len(files),'output_bytes':sum(f['size'] for f in files),'files':files,'segment_count':0})
    return {'status':'local_complete_cloud_pending','new_recordings':fl['segment_count'],
            'source_completed_recordings':fl['snapshot_segment_count'],'cutoff_epoch_ms':sr['snapshot_export_epoch_ms'],
            'excluded_partials':fl['verification']['excluded_partial_segments'],
            'preferred_segments':sum(r['diagnostics']['asr_segment_count'] for r in catalog['recordings']),
            'pdf':str(pdf),'pdf_sha256':sha(pdf),'pdf_pages':read(batch/'pdf-text-qa.json')['pages'],
            'archive':str(archive),'archive_sha256':sha(archive),'human_visual_review_performed':False}
