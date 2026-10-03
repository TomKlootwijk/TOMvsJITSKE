# Private Recorder 1.2

A personal Android microphone recorder for a Poco X7 Pro or another Android 10+ phone. Start it visibly, then turn off the screen. It saves room audio on the phone without depending on Wi-Fi or mobile data. It retains Android's microphone indicator, recording notification, launcher icon, and Stop control.

Version 1.2 adds **Export individual files to folder**: finished audio files and their integrity records can be copied separately, with matching existing copies verified and skipped on repeat exports. ZIP snapshots remain available.

The automatic **Google Drive and OneDrive** backup code introduced in 1.1 remains included. Each account connects separately. Recording continues locally when either cloud is unavailable; pending backups retry when Android permits background work and a suitable network is available. Native cloud sign-in setup and tests on the actual Poco remain outstanding; see [cloud setup](docs/CLOUD_SETUP.md) and [validation status](docs/VALIDATION.md).

**Release status, 2026-09-27:** `dist/PrivateRecorder-1.2.apk` is signed and ready, but has **not been installed**. The physical Poco remains on 1.1. Folder export has automated tests but has not been tested through the phone's folder picker. Neither native cloud provider is connected or validated on the handset; desktop cloud copies are a separate workflow.

**Handset observations, 2026-09-25:** the signed 1.1 APK was installed on the physical Poco X7 Pro over USB. Microphone and notification permissions were granted; the app showed Recording and initial saved bytes. The short screen-off check passed: Android reported active, unsilenced microphone capture, the foreground service, and a partial wake lock while the display was dozing. The package was present in Android's user idle whitelist. These observations do not verify playback/audibility, continuous capture, or live cloud backups.

**This is a new implementation, not a validated evidence appliance. No phone app can promise 24/7/365 zero downtime.** Calls, another microphone app, Android/HyperOS, force-stop, reboot, thermal/power problems, and storage exhaustion can interrupt capture. Test the actual phone before relying on it. Use an independently powered second recorder for an important conversation if missing audio would matter.

## Install and use

1. Build or obtain `dist/PrivateRecorder-1.2.apk`. For an existing recording installation, first stop, finish saving, and export/verify independent copies; then update over the existing app and explicitly restart recording. Transfer the APK over USB or a method you trust and open it on the phone. Allow installation for that transfer/file app when Android prompts. Building an APK does not install or test it on the phone.
2. Open **Private Recorder**, tap **Start recording**, and allow microphone and notification access. Start is a deliberate action; installing the APK does not start capture.
3. In HyperOS, set this app's battery setting to **No restrictions**, enable **Background autostart**, and lock the app in Recents or the Security app's **Boost speed → Lock apps** setting, if those controls exist on your build. These reduce some interruptions; they do not override Android microphone restrictions. There is no verified need to disable Memory Extension.
4. Keep the phone charged, with adequate free storage, and test screen-off capture. Check the recording status and actual audio daily. Capture does not keep the display on; version 1.2 keeps the foreground export screen on while copying files.
5. After a reboot, force-stop, process failure, or a persistent recording error, open the app and tap **Start recording** again. While the foreground service is still alive, the app makes up to three logged retries for specifically recoverable microphone/codec failures; each retry can leave a gap. If Android delivers the boot broadcast and permits notifications, the app posts a restart reminder. It does not start the microphone from the boot receiver.
6. Choose **Export individual files to folder** for separate audio/metadata files, or **Export evidence ZIP** for a ZIP snapshot including the full journal prefix. Keep the app open until export completes. Both exclude the active audio segment. To include it, use **Stop** and wait for saving first. The installed 1.1 version calls its ZIP button **Export evidence snapshot**; the individual-folder button is new in 1.2.
7. Open **Google Drive & OneDrive backups**, connect each account, and tap **Sync saved files now**. Complete the provider setup in [CLOUD_SETUP.md](docs/CLOUD_SETUP.md) first. Review each provider's status independently and open both backup folders to confirm the saved test files.

Uninstalling or clearing app data deletes the phone archive. Export and verify independent copies first. Updating an installed APK with the same signing key preserves app data; an update can still interrupt recording, so stop/save/export first and explicitly restart afterward.

## What it saves

- AAC-LC in `.aac` ADTS files: mono, 48 kHz, requested 64 kbit/s. The same AudioRecord and encoder continue across approximately five-minute file boundaries. Splitting does not deliberately restart capture. Device/encoder behavior and audible boundaries still need handset testing.
- Roughly **0.7 GB/day**, **21 GB/30 days**, or **250–265 GB/year**, including an estimate for ADTS overhead. Actual encoder bitrate and metadata affect the total. Storage available to apps is less than the capacity printed on the phone.
- A metadata JSON beside each finalized audio file, with a SHA-256 digest, segment/session identifiers, recording configuration, device-clock timestamps and elapsed-time values. Audio timeline times are estimates anchored to the session clock; they are not certified timestamps.
- An append-only `events.jsonl` journal containing starts, stops, failures, segment commits, observed audio routing, detected device-clock changes, and Android-reported microphone silencing. The capture state is sampled approximately once a second; brief events or sudden process death may not be logged.
- Active audio has an `.aac.partial` suffix. A subsequent explicit Start preserves surviving interrupted audio as `.interrupted.aac`, including possibly incomplete final frames, and labels unknown end times/durations as unknown. It does not fabricate missing audio. ADTS often allows intact earlier frames to remain decodable after a crash, but this is not a guarantee of recovery.

The app flushes active audio to phone storage about every five seconds and on finalization. Power loss can still lose recent writes or damage a partial frame. It stops with a 256 MiB storage reserve and never automatically deletes earlier recordings, including recordings already backed up. It does not skip silence, transcribe, enhance, or edit audio. Android/the handset may apply microphone processing; the saved original is the app's encoded recording, not raw forensic PCM.

## Automatic cloud backups

| Provider | Destination | Requested access |
| --- | --- | --- |
| Google Drive | `My Drive/Private Recorder Evidence/<bundle>/` | `drive.file`: files created or opened through this app |
| OneDrive | `Apps/<Microsoft app name>/Private Recorder Evidence/<bundle>/` | `Files.ReadWrite.AppFolder`, with `offline_access` for ongoing sign-in |

Cloud connections start disabled. Connecting an account enables its automatic backup. The **Back up only on unmetered networks (usually Wi-Fi)** switch defaults to **off**, so enabled backups can use Wi-Fi or mobile data. Turning it on follows Android's unmetered-network classification; a metered Wi-Fi network can still be excluded.

Only finalized audio and committed metadata/event snapshots enter the upload queue. The current approximately five-minute audio segment stays local until it closes. There are separate durable jobs and receipts for each provider; one provider failing does not block recording or the other provider. Finished segments trigger work, and a periodic job provides another opportunity roughly every 15 minutes. Android can delay either job, so these are scheduling requests, not promised delivery times.

Before accepting an upload, the app downloads its remote bytes and compares SHA-256 hashes against the local files. Existing files are checked instead of overwritten. Conflicts leave local originals intact and appear in backup status. A saved receipt records a successful comparison at that time. It is **not continuous monitoring of cloud files**: later deletion, modification, account loss, or a cloud outage can make that historical copy unavailable. The app does not automatically audit every previously verified remote file on each run.

Full verification uses both upload and download data. At approximately 0.7 GB of new audio per day, both clouds together need about **2.8 GB/day of combined upload/download traffic** before retries and overhead. Each cloud also accumulates its own approximately 0.7 GB/day copy; the phone keeps its original archive. Check mobile allowances and storage quota in all three places. Disconnecting a provider stops future app backup work and leaves existing local and remote files intact.

## Preserve and verify exports

Keep original exports unedited. Retain at least two independent copies and note when, where, and on which device each conversation was recorded. Keep any transcript or excerpt separately. Ask your lawyer how to transfer the originals and document custody.

The 1.2 folder option writes individual AAC files, sidecars, and immutable event deltas/checkpoints into a selected folder. It reads copies back and checks hashes; matching files are skipped, while differing or ambiguous names are preserved and reported. Choose a provider supporting file rename, such as a device folder. An interrupted copy can leave a `Pending_...` file; retrying the same folder can complete missing copies without replacing existing files. Each run saves a unique `SHA256SUMS_<epoch>_<UUID>.txt` for fully verified bundles. This format does **not** include the complete root `events.jsonl` or ZIP `export_metadata.json`; retain ZIP snapshots as well when those records are needed. The ZIP verifier below does not accept this folder format.

Each ZIP contains `SHA256SUMS`. On a computer with Python 3.9 or later:

```powershell
python tools/verify_export.py C:\path\to\PrivateRecorder_export.zip
```

To turn a ZIP into separate files organized by recorded UTC date:

```powershell
python tools/organize_export.py C:\path\to\PrivateRecorder_export.zip C:\path\to\new-individual-files
```

The organizer verifies the ZIP, preserves its bytes, and checks every copied file before publishing the new output folder. Audio and unchanged metadata go into `audio/YYYY-MM-DD/`; missing or invalid start dates use `audio/undated/`. `INDEX.csv` lists recordings, `SHA256SUMS.csv` maps original paths to new paths and hashes, and `ORGANIZATION.json` records the original ZIP hash. The original global journal and export metadata are retained. A sibling `new-individual-files.filelist.json` lists all output files for later copy verification. Use a new output name under an existing parent directory: existing destinations are rejected. If you have a separately recorded trusted ZIP digest, supply it with `--expected-sha256`. This operation does not listen, transcribe, enhance, or change audio.

The verifier checks archive paths, file coverage and hashes; see [export-format.md](docs/export-format.md). Hashes can detect changes relative to a trusted earlier hash. A hash packaged with a recording does **not** prove who spoke, that a timestamp is accurate, that capture was complete, that content was never altered before hashing, or that a court will admit it. Preserve the initial ZIP hash separately with your lawyer.

The archive is stored in app-private storage excluded from Android's automatic device backup. Versions 1.1 and 1.2 have Internet permission for explicitly connected cloud accounts. Sign-in state is encrypted with a device-bound Android Keystore key; transfers use HTTPS. Recordings, cloud file contents, and exports are **not password-encrypted by this app**. Protect the phone and both cloud accounts. Cloud upload receipts remain in the app's private storage; they are separate from export hash manifests. A manual export destination chosen through Android's document picker is handled by that destination provider.

## Netherlands context

You said you will participate in the conversations. The Dutch psychologists' professional association discusses recording your own conversation and refers to Article 139a of the Criminal Code in its [guide to law and regulation for psychologists](https://psynip.nl/wp-content/uploads/pdfs/Wegwijzer-wet-en-regelgeving-voor-psychologen-in-de-gezondheidszorg.pdf). The [KNMG guidance](https://www.knmg.nl/actueel/dossiers/patientenrechten/opnemen-van-het-gesprek) also explains that a patient can record their own doctor consultation for private use without having to announce it. This is not a blanket permission to record conversations you are not part of or to publish recordings. Have your lawyer confirm the collection and use appropriate to your particular case; do not assume recording permission establishes admissibility or permission to share publicly.

## Build

Windows PowerShell scripts download a portable JDK 17, Android SDK 35 and Gradle 8.11.1 under `.tools`, with checksums where supplied. Android Gradle Plugin is pinned to 8.9.2. Nothing is added to global PATH.

```powershell
.\setup-tools.ps1
.\build.ps1
.\package.ps1
```

`build.ps1` runs debug compilation, JVM unit tests, and Android lint. `package.ps1` produces the signed non-debuggable APK. Preserve the private `.signing` directory securely for future updates; it must not be committed or shared with the APK. The Gradle wrapper also works in Android Studio with an installed JDK/SDK. Portable tool downloads and build outputs are excluded from Git.

## Handset validation before use

These checks have to be performed on the actual Poco; compilation/unit tests cannot establish microphone reliability:

1. Record recognizable speech, turn the screen off for at least 30 minutes, then inspect and listen to every segment and the boundaries. Check the input is the intended microphone.
2. Run a 24–72 hour trial in the same charging, placement, case and network conditions you will use. Compare the captured durations and session events against elapsed time. Listen to samples throughout; a growing file can contain silence.
3. Test incoming/outgoing cellular calls, VoIP, voice messages, camera recording, assistant use, Bluetooth changes, battery saver, locking and clearing Recents. Inspect both the events and the actual audio. It is **not** a telephone-call recorder: Android can give calls/other apps microphone priority or silence this app.
4. In a disposable test session, force-stop or reboot during recording. Confirm the interruption is visible, explicitly restart, then check the recovered partial and the missing interval. Do not assume notification delivery or automatic recovery is guaranteed.
5. Test ZIP and individual-folder export during recording and after stopping. For the folder option, repeat an export to confirm matching files are reused and new completed files appear; test a conflicting destination without altering originals. Verify ZIPs, decode/play the audio, and verify copied files on a second device. Do not clear original data based only on a success message.
6. Connect both cloud accounts and finish a disposable recording. Confirm each account contains the expected audio and metadata, download both copies independently, compare hashes, and listen to the audio. Repeat with the screen off, with the network unavailable and restored, and with each provider disconnected/reconnected. Check Wi-Fi/mobile behavior against the selected network policy. The existing automated tests do not replace these account/device checks.

## Technical references

- [Android foreground microphone service requirements](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone)
- [Android background service start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Android microphone restrictions after boot](https://developer.android.com/about/versions/15/changes/foreground-service-types)
- [Android audio-input sharing and silencing](https://developer.android.com/media/platform/sharing-audio-input)
- [Xiaomi background settings guidance](https://www.mi.com/global/support/faq/details/KA-515628/)
- [NIST digital evidence preservation guidance](https://www.nist.gov/publications/digital-evidence-preservation-considerations-evidence-handlers)
