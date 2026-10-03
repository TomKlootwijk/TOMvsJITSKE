"""Opt-in local end-to-end synthetic test: USB/cloud replaced, ASR/PDF/ZIP real.

Run this file directly. It retains its tiny fixture and test receipts under
evidence/.standalone/selftests, never processing existing recording audio.
"""
import io,json,uuid,wave,hashlib
from pathlib import Path
from unittest.mock import patch
from recorder_backup_common import ROOT,config,sha,publish,read
from test_verify_export import make_archive
from test_organize_incremental_export import add_segment
from organize_export import organize_export
import recorder_backup_pipeline as pipeline

def run():
    work=ROOT/'evidence/.standalone/selftests'/uuid.uuid4().hex;work.mkdir(parents=True)
    prior=work/'2026-09-29-000000';prior.mkdir();old=add_segment({},'old_fixture')
    z=prior/'prior.zip';z.write_bytes(make_archive(old).getvalue());organize_export(z,prior/'individual-files')
    files=add_segment(old,'synthetic_000000_1790683200000')
    name='recordings/synthetic_000000_1790683200000.aac'
    b=io.BytesIO()
    with wave.open(b,'wb') as w:w.setnchannels(1);w.setsampwidth(2);w.setframerate(16000);w.writeframes(b'\x00\x00'*16000)
    files[name]=b.getvalue();side=json.loads(files[name+'.json']);side.update(size_bytes=len(files[name]),sha256=hashlib.sha256(files[name]).hexdigest());files[name+'.json']=json.dumps(side).encode()
    files['export_metadata.json']=json.dumps({'schema_version':1,'finalized_segments':2,'export_epoch_ms':1790683200000,'excluded_partial_segments':1}).encode()
    batch=work/'2026-09-29-120000';batch.mkdir();(batch/'working').mkdir();(batch/'original-export').mkdir()
    source=batch/'original-export/PrivateRecorder_20260929_120000Z.zip';source.write_bytes(make_archive(files).getvalue())
    publish(batch/'source-transfer-receipt.json',{'laptop_path':str(source),'laptop_sha256':sha(source),'size':source.stat().st_size,'snapshot_export_epoch_ms':1790683200000})
    with patch.object(pipeline,'EVIDENCE',work):
        first=pipeline.process(batch,prior/'individual-files.filelist.json',config())
        pinned={str(p.relative_to(batch)):sha(p) for p in batch.rglob('*') if p.is_file()}
        with patch.object(pipeline,'call',side_effect=AssertionError('Completed local stages must not run again')):
            second=pipeline.process(batch,prior/'individual-files.filelist.json',config())
        after={str(p.relative_to(batch)):sha(p) for p in batch.rglob('*') if p.is_file()}
    assert pinned==after,'Resume changed frozen files'
    assert first['new_recordings']==second['new_recordings']==1
    assert first['archive_sha256']==second['archive_sha256']
    assert not first['human_visual_review_performed']
    report={'status':'passed','test':'synthetic source -> new-only organization -> actual pinned local ASR -> PDF text/order/bounds/render -> ZIP member hashes -> no-change resume',
            'real_phone_or_cloud_used':False,'existing_recordings_reprocessed':False,'result':first,'unchanged_files_on_resume':len(pinned)}
    publish(work/'test-result.json',report);print(json.dumps({'test_report':str(work/'test-result.json'),'status':'passed','unchanged_files_on_resume':len(pinned)},indent=2))

if __name__=='__main__':run()
