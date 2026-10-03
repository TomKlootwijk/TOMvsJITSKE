"""Safety and restart tests use synthetic data and fake transports, never real clouds/phone."""
import io,json,os,struct,tempfile,unittest,zipfile,shutil
from pathlib import Path
from unittest.mock import patch
from recorder_backup_common import Lock,Pending,publish,read,sha,contained,latest_prior
from recorder_backup_phone import eocd_complete,reserve_ok,parse_ui,Phone,select_source,copy_source
from recorder_backup_pipeline import archive_members,verify_package,new_manifest,organize_batch
from recorder_backup_cloud import private_metadata,relative_safe,Cloud,transfer_scope

class BackupTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
    def test_complete_zip_rejects_truncation_and_trailing_bytes(self):
        b=io.BytesIO()
        with zipfile.ZipFile(b,'w') as z:z.writestr('a',b'bytes')
        data=b.getvalue()
        self.assertTrue(eocd_complete(data,len(data)))
        self.assertFalse(eocd_complete(data[:-1],len(data)-1))
        self.assertFalse(eocd_complete(data+b'garbage',len(data)+7))
    def test_phone_reserve_includes_export(self):
        g=1024**3
        self.assertFalse(reserve_ok(22*g,100*g,3*g,20*g))
        self.assertFalse(reserve_ok(30*g,15*g,3*g,20*g))
        self.assertTrue(reserve_ok(23*g,20*g,3*g,20*g))
    def test_publish_never_overwrites_conflict(self):
        p=self.root/'data';publish(p,b'first');publish(p,b'first')
        with self.assertRaises(Pending):publish(p,b'different')
        self.assertEqual(p.read_bytes(),b'first')
    def test_lock_prevents_two_concurrent_runs(self):
        with Lock(self.root/'lock'):
            with self.assertRaises(Pending):
                with Lock(self.root/'lock'):pass
        with Lock(self.root/'lock'):pass
    def test_latest_prior_accepts_post_pull_phone_laptop_receipt(self):
        old=self.root/'2026-09-29-143709';old.mkdir()
        new=self.root/'2026-10-02-144331';new.mkdir()
        publish(old/'individual-files.filelist.json',{'original_archive_sha256':'old','snapshot_segment_count':1279})
        publish(old/'source-transfer-receipt.json',{'status':'phone_and_laptop_sha256_verified','laptop_sha256':'old','snapshot_export_epoch_ms':1790696229000})
        publish(new/'individual-files.filelist.json',{'original_archive_sha256':'new','snapshot_segment_count':1395})
        publish(new/'source-transfer-receipt.json',{'status':'phone_post_and_laptop_sha256_verified','phone_sha256_before_pull':'new','phone_sha256_after_pull':'new','laptop_sha256':'new','snapshot_export_epoch_ms':1790952213461})
        self.assertEqual(latest_prior(self.root),new/'individual-files.filelist.json')
    def test_fresh_xml_required_and_record_controls_blocked(self):
        state=parse_ui('<hierarchy><node package="nl.privaterecorder" text="12 finished segments · 2.0 GiB saved"/></hierarchy>')
        self.assertEqual(state['finished'],12)
        phone=Phone({})
        with self.assertRaises(Pending):phone.tap(state,'Stop')
    def test_private_check_rejects_links_and_unknown_permissions(self):
        self.assertTrue(private_metadata({'Metadata':{'permissions':'[]'}},'google'))
        for item in ({'Metadata':{}},{'Metadata':{'permissions':'[{"type":"anyone","role":"reader"}]'}}):
            with self.assertRaises(Pending):private_metadata(item,'google')
        with self.assertRaises(Pending):private_metadata({'Metadata':{'permissions':'[]','shared-scope':'anonymous'}},'onedrive')
    def test_unsafe_transfer_paths_rejected(self):
        for p in ('../file','/file','C:/file','a\\b','file\nnext'):
            with self.assertRaises(Pending):relative_safe(p)
        with self.assertRaises(Pending):contained(self.root,'../outside')
    def test_archive_readback_detects_corruption_and_missing_members(self):
        m={'files':[{'archive_path':'batch/original.aac','size':4,'sha256':__import__('hashlib').sha256(b'test').hexdigest()}]}
        expected,meta,sums=archive_members(m);p=self.root/'archive.zip'
        with zipfile.ZipFile(p,'w') as z:z.writestr('batch/original.aac',b'test');z.writestr('PACKAGE_MANIFEST.json',meta);z.writestr('SHA256SUMS',sums)
        self.assertEqual(verify_package(p,m),3)
        bad=self.root/'bad.zip'
        with zipfile.ZipFile(bad,'w') as z:z.writestr('batch/original.aac',b'nope');z.writestr('PACKAGE_MANIFEST.json',meta);z.writestr('SHA256SUMS',sums)
        with self.assertRaises(Pending):verify_package(bad,m)
    def test_existing_remote_mismatch_is_never_uploaded(self):
        p=self.root/'x';p.write_bytes(b'local');cloud=Cloud.__new__(Cloud);cloud.provider='google'
        cloud.ensure_directory=lambda _:None;cloud.child=lambda *_:{'IsDir':False,'Size':5}
        cloud.stat=lambda _:{'Metadata':{'permissions':'[]'}};cloud.hash_remote=lambda _:(5,'bad')
        cloud.run=lambda *_:self.fail('Must never upload on conflict')
        with self.assertRaises(Pending):cloud.transfer(p,'folder/x',sha(p),5)
    def test_existing_remote_match_is_reconciled_without_upload(self):
        p=self.root/'x';p.write_bytes(b'local');cloud=Cloud.__new__(Cloud);cloud.provider='google'
        cloud.ensure_directory=lambda _:None;cloud.child=lambda *_:{'IsDir':False,'Size':5}
        cloud.stat=lambda _:{'Metadata':{'permissions':'[]'}};cloud.hash_remote=lambda _:(5,sha(p))
        cloud.run=lambda *_:self.fail('Must not upload a confirmed copy')
        self.assertEqual(cloud.transfer(p,'folder/x',sha(p),5)['disposition'],'existing_copy_reconciled')
    def test_existing_connector_receipt_skips_network(self):
        source=self.root/'original';source.mkdir();p=source/'a.aac';p.write_bytes(b'audio')
        m={'output_directory':str(source),'files':[{'relative_path':p.name,'size':5,'sha256':sha(p)}]}
        publish(self.root/'individual-files.filelist.json',m)
        (self.root/'google-drive-readback.jsonl').write_text(json.dumps({'relative_path':p.name,'size':5,'sha256':sha(p),'verified':True})+'\n')
        with patch('recorder_backup_cloud.Cloud',side_effect=AssertionError('Network should not run')):
            r=transfer_scope(self.root,{'google_remote':'test:'},'google')
        self.assertEqual(r['status'],'complete_remote_sha256_verified')
    def test_no_new_recordings_never_exports(self):
        prior=self.root/'individual-files.filelist.json';publish(prior,{'original_archive_name':'PrivateRecorder_20260929_113214Z.zip','snapshot_segment_count':1252})
        class Fake:
            def exports(self):return ['PrivateRecorder_20260929_113214Z.zip']
            def ui(self):return {'recorder':True,'finished':1252,'exporting':False}
        self.assertEqual(select_source(Fake(),prior,False,{},self.root/'state'),(None,False))
    def test_writing_export_never_falls_through_to_new_export(self):
        prior=self.root/'prior';publish(prior,{'original_archive_name':'PrivateRecorder_20260929_113214Z.zip'})
        class Fake:
            c={'phone_export_directory':'/approved'}
            def exports(self):return ['PrivateRecorder_20260929_123214Z.zip']
            def completed(self,_):return None
            def ui(self):raise AssertionError('Must not inspect/tap a new export')
        with self.assertRaises(Pending):select_source(Fake(),prior,False,{},self.root/'state')

    def test_scheduled_pull_cannot_bypass_hourly_gate(self):
        prior=self.root/'individual-files.filelist.json'
        publish(prior,{'original_archive_name':'PrivateRecorder_20260929_113214Z.zip','snapshot_segment_count':1252})
        publish(self.root/'source-transfer-receipt.json',{'snapshot_export_epoch_ms':1000000})
        class Fake:
            c={'minimum_export_interval_seconds':3600}
            def exports(self):return []
            def ui(self):return {'recorder':True,'finished':1253,'exporting':False}
        with patch('recorder_backup_phone.time.time',return_value=1100):
            with self.assertRaisesRegex(Pending,'hourly'):select_source(Fake(),prior,True,{},self.root/'state')

    def test_failed_ui_dump_invalidates_previous_state(self):
        phone=Phone({});phone.last_ui=(0,{'old':True})
        phone.shell=lambda command,**_: 'window unlocked' if command=='dumpsys window' else 'ERROR: could not get idle state'
        with self.assertRaisesRegex(Pending,'Fresh'):phone.ui()
        self.assertIsNone(phone.last_ui)

    def test_pending_export_intent_never_clicks_again(self):
        prior=self.root/'prior';publish(prior,{'original_archive_name':'PrivateRecorder_20260929_113214Z.zip'})
        class Fake:
            def exports(self):return []
            def ui(self):return {'recorder':True}
            def tap(self,*_):raise AssertionError('Duplicate export click')
        with self.assertRaisesRegex(Pending,'no second export'):select_source(Fake(),prior,False,{'export_clicked':True},self.root/'state')

    def test_zero_new_collection_releases_coordinator_and_preserves_cloud_holds(self):
        import recorder_backup as runner
        batch=self.root/'2026-09-29-120000';batch.mkdir()
        publish(batch/'source-transfer-receipt.json',{'snapshot_export_epoch_ms':1790683200000,'exported_recordings':1252})
        publish(self.root/'pending-phone-backup.json',{'current_processing_snapshot':batch.name,'old_cloud_hold':'retain'})
        with patch.object(runner,'EVIDENCE',self.root):
            runner.update_queue(batch,{}, {'status':'source_verified_no_new_recordings'})
        q=read(self.root/'pending-phone-backup.json')
        self.assertIsNone(q['current_processing_snapshot'])
        self.assertEqual(q['old_cloud_hold'],'retain')

    def test_usb_copy_verifies_three_hashes_and_reuses_completed_source(self):
        self.check_usb_copy(False)

    def test_usb_change_retains_partial_without_publishing(self):
        self.check_usb_copy(True)

    def check_usb_copy(self,change_after):
        from test_verify_export import make_archive
        from test_organize_incremental_export import add_segment
        files=add_segment({},'usb_fixture')
        files['export_metadata.json']=json.dumps({'schema_version':1,'finalized_segments':1,'export_epoch_ms':1790683200000,'excluded_partial_segments':1}).encode()
        source=self.root/'fixture.zip';source.write_bytes(make_archive(files).getvalue())
        batch=self.root/'batch';batch.mkdir();expected=sha(source);size=source.stat().st_size
        class Fake:
            c={'serial':'synthetic-test-only'}
            calls=0
            def completed(self,_):return size,123
            def stat(self,_):return size,123
            def sha(self,_):
                self.calls+=1
                return '0'*64 if change_after and self.calls>1 else expected
            def adb(self,*args,**kwargs):
                if args[:2]!=('pull','-a'):raise AssertionError('Unexpected device mutation')
                shutil.copy2(source,Path(args[3]))
        phone=Fake();remote='/approved/PrivateRecorder_20260929_120000Z.zip'
        with patch('recorder_backup_phone.shutil.disk_usage',return_value=shutil._ntuple_diskusage(100*1024**3,0,100*1024**3)):
            if change_after:
                with self.assertRaisesRegex(Pending,'changed during copy'):copy_source(phone,remote,batch,True,{})
                self.assertFalse((batch/'source-transfer-receipt.json').exists())
                self.assertFalse((batch/'original-export'/Path(remote).name).exists())
                self.assertEqual(len(list((batch/'original-export').glob('*.partial-*'))),1)
            else:
                r=copy_source(phone,remote,batch,True,{})
                self.assertEqual({r[k] for k in ('phone_sha256_before_pull','phone_sha256_after_pull','laptop_sha256')},{expected})
                phone.adb=lambda *_args,**_kw:self.fail('A completed source must not be copied again')
                self.assertEqual(copy_source(phone,remote,batch,True,{}),r)

    def test_organization_recovers_before_and_after_directory_publication(self):
        from test_verify_export import make_archive
        from test_organize_incremental_export import add_segment
        from organize_export import organize_export
        import organize_incremental_export as organizer
        original=add_segment({},'old');prior_zip=self.root/'prior.zip';prior_zip.write_bytes(make_archive(original).getvalue())
        organize_export(prior_zip,self.root/'prior')
        prior=self.root/'prior.filelist.json'
        previous={p:sha(p) for p in (self.root/'prior').rglob('*') if p.is_file()}
        source=self.root/'new.zip';source.write_bytes(make_archive(add_segment(original,'new')).getvalue())
        for point in ('before_rename','before_manifest'):
            with self.subTest(point=point):
                batch=self.root/point;batch.mkdir();(batch/'working').mkdir()
                real_publish=organizer._publish_transaction_file
                def fail_manifest(path,value):
                    if Path(path).name=='individual-files.filelist.json':raise OSError('Simulated publication interruption')
                    return real_publish(path,value)
                fault=patch.object(Path,'rename',side_effect=OSError('Simulated rename interruption')) if point=='before_rename' else patch.object(organizer,'_publish_transaction_file',side_effect=fail_manifest)
                with fault:
                    with self.assertRaises(OSError):organize_batch(batch,source,prior,sha(source))
                self.assertTrue((batch/'working/organization-commit.json').exists())
                with patch.object(organizer,'organize_incremental',side_effect=AssertionError('Must reuse staged files')):
                    fp=organize_batch(batch,source,prior,sha(source))
                result=read(fp);self.assertEqual(result['segment_count'],1)
                for f in result['files']:self.assertEqual(sha(batch/'individual-files'/f['relative_path']),f['sha256'])
        self.assertEqual(previous,{p:sha(p) for p in previous})

if __name__=='__main__':unittest.main()
