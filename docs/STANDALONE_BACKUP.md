# One-click Private Recorder backup

Double-click **Recorder Backup.cmd** in the project folder. This is a standalone
Python program: it does not launch Codex, call an OpenAI API, or require a Codex
account. Keep this project and its `.tools` folder together.

Connect the already authorized Poco by USB, unlock it, and leave Private Recorder
visible. The tool resumes unfinished work first. It then ingests an existing
completed export, or uses the normal **Export evidence snapshot** button when new
finished recordings are visible. If Android's Save dialog opens somewhere else,
choose the existing `Download/F8 bedankt onwijs van mijzelf` folder once and save.
Run the launcher again; it remembers that an export was already requested.

The phone can be disconnected when the console says **Source verified. Safe to
unplug**. The remaining transcription and upload work uses the laptop copy.

## One-time cloud setup

Local processing and the existing OneDrive sync copy work with the tools already
installed on this PC. Direct Google Drive and OneDrive remote verification need
their own sign-in; Codex's connector sign-in is not available to a standalone
program. Until setup is complete, local outputs are retained and the result says
`local_complete_cloud_pending`, never “fully backed up”.

1. Double-click **Recorder Cloud Setup.cmd**.
2. In the rclone wizard create `recorder-google` with storage type `drive`, and
   `recorder-onedrive` with type `onedrive`. Use the same accounts as the existing
   evidence backups. Select the existing personal OneDrive.
3. Google may require your own **Desktop app OAuth client**. Follow the official
   [rclone Google Drive client setup](https://rclone.org/drive/#making-your-own-client-id).
   The shared client is being retired in 2026. Use access that can read the existing
   evidence folder: `drive.file` alone cannot see files uploaded by other apps.
   Review the provider's consent screen yourself; the tool does not consent for you.
4. Quit the wizard after saving both remotes. The script checks the destination
   folders and their private permissions, then reports whether setup succeeded.
5. Double-click **Recorder Backup.cmd** to resume pending uploads.

Google uploads are rooted at the existing evidence folder
`1Pr4bOvW-aizD9upFl8gpEA9CsH1l2GUG`; OneDrive uses `Private Recorder Evidence`.
The tool never creates sharing links or changes cloud permissions. A shared,
ambiguous, missing, or uninspectable destination is a hold, not a success.
Credentials are stored in `%LOCALAPPDATA%\PrivateRecorderBackup\rclone.conf`,
inside a user-only Windows folder, outside the repository and all upload scopes.
Do not send that file in chat or commit it.

The bundled transport is official **rclone v1.75.1**, downloaded from
[rclone's release server](https://downloads.rclone.org/v1.75.1/) and checked against
its published SHA256SUMS. It is under `.tools/rclone/`. Its executable SHA-256 is
`033eee51c9ad47c2de2624b6674d355274bcd6cf0027a5f85db4437ba24ae81c`.
Rclone handles provider upload protocols directly; the Codex connector's 100 MiB
upload limit does not apply. See the official [Drive](https://rclone.org/drive/)
and [OneDrive](https://rclone.org/onedrive/) documentation.

## What a run does

- Locks the coordinator so a second standalone run cannot collect concurrently.
- Reads the latest verified prior and resumes its own unfinished jobs before a new collection.
- Uses only the authorized USB serial. It never pairs a new device, enables wireless
  debugging, unlocks the phone, launches/stops capture, reinstalls, or changes settings.
- Requires fresh UI data for every export action. A failed UI dump is not reused.
- Checks completed exports first, phone/laptop space, and new finished recordings.
  It reserves at least **20 GiB on the phone after the estimated export** and enough
  laptop space for source, derivatives and archive. It never deletes earlier exports.
- Checks phone SHA-256 before and after copying, laptop SHA-256, ZIP completeness,
  every member CRC, and original bundle hashes. Completed source partials are retained.
- Organizes only missing originals. Previous names, bytes, metadata, labels and
  confirmed transcripts remain unchanged. Changed immutable files cause a hold.
- Runs the pinned local small ASR model with per-recording language detection.
  Repetitive or low-confidence drafts receive retained medium alternatives.
  Small remains the automatic preference; editorial comparison needs listening review.
- Produces timestamped JSON/text drafts, conservative draft categories, a dated PDF,
  source hashes, a local index and a verified ZIP archive. It checks complete PDF
  text/order/bounds and renders every page. **It does not claim human visual review.**
  No returned speech segments does not establish silence.
- Copies to the existing OneDrive sync folder, separately from remote verification.
  With cloud setup complete, uploads missing files to both destinations and streams
  every unconfirmed remote file through a full SHA-256 readback. Existing matching
  files are reconciled; conflicts are never overwritten. Completed receipts skip
  repeat uploads and downloads.

Earlier Codex-created collections and their suppressed cloud holds are not picked
up as standalone jobs. In particular, the three existing oversized Google archive
holds and old failed OneDrive downloads remain untouched. They can be reconciled
separately after cloud setup; the launcher does not silently change their status.

## Status and recovery

Results are under `evidence/<export date and time>/`:

- `standalone-job.json`: resumable work and source request identity.
- `standalone-result.json`: cutoff, excluded partials, local artifacts and separate
  provider results. Exit code **0** means this run completed/no new recordings;
  **2** means work remains pending; **1** is an unexpected failure.
- `google-standalone-readbacks.json`, `onedrive-standalone-readbacks.json`:
  per-file confirmed remote bytes, in each original/artifact scope.
- `onedrive-standalone-local.json`: local sync hashes only.
- `derived/START HERE.html`: opens the PDF, archive and source index.

Rerunning resumes checkpoints. An interrupted source transfer is retained; a fresh
copy may be needed because `adb pull` has no reliable byte-range resume. A completed
matching partial is reused. An interrupted export is not requested a second time.
If it failed on the phone, complete a normal export manually and rerun.

Optional commands from a terminal in this folder:

```bat
"Recorder Backup.cmd" --status
"Recorder Backup.cmd" --preflight
"Recorder Backup.cmd" --resume
"Recorder Backup.cmd" --scheduled
```

`--status` reads local state only. `--preflight` checks local dependencies/model
pins. `--resume` never starts a new export. `--scheduled` enforces the one-hour gate;
an ordinary manual double-click is a new explicit pull. No scheduler is installed.
The existing Codex heartbeat must respect `evidence/.standalone/backup.lock` and
pending `standalone-job.json` files before collecting. A pending foreign coordinator
in `pending-phone-backup.json` also prevents the launcher from starting a new batch.

Advanced paths can be overridden in `recorder-backup.local.json` (git-ignored).
Defaults are in `tools/recorder_backup_common.py`. The default Python and PDF
libraries reuse this PC's installed runtime; they require no running Codex session.
For a machine without that runtime, create `.tools/backup-venv` with Python 3.12
and install ReportLab, pypdf, pdfplumber and Pillow, then set `pdftoppm` to an installed
Poppler executable. The existing `.tools/asr-venv`, pinned models and ADB must also
be present. The tool does not download models during a backup.

## Validation

Safety tests cover ZIP truncation, disk reserve, exclusive publication, concurrent
runs, stale UI rejection, remote privacy checks, path traversal, archive corruption,
cloud conflicts, remote deduplication, reuse of confirmed connector receipts,
the hourly gate, changed USB source hashes, completed-source reuse, and recovery
both before and after organized-directory publication.
An opt-in synthetic test runs actual local ASR, PDF generation, full text/bounds and
render checks, ZIP hashes and a no-change resume without touching the real phone,
cloud services or existing recordings:

```bat
python -m unittest discover -s tools -p test_recorder_backup.py -v
python tools/test_recorder_backup_integration.py
```

Direct provider authentication and live standalone cloud transfer remain untested
until the one-time sign-in is completed. The current batch's separate Codex cloud
receipts are independent of that limitation.

The delivery check passed 20 standalone safety tests, six existing incremental
organizer tests, local dependency/model preflight, and the actual `.cmd --status`
entrypoint. The synthetic end-to-end run retained 54 files unchanged on its second
run. Phone operations used a fake USB transport for tests; no extra real export
was created to test the launcher. See `evidence/.standalone/implementation-validation.json`.
