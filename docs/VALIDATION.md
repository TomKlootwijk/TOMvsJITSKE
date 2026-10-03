# Validation

## Version 1.2 — individual-file folder export

Built and checked on 2026-09-27 in the Windows workspace. **Ready but not installed:** the physical Poco remains on 1.1. No phone installation or interaction was performed during this release build.

| Check | Result |
| --- | --- |
| Signed release build, unit tests, and lint (`package.ps1`) | Passed |
| Java unit tests | 56 passed, zero failures/errors, including 7 folder-export tests |
| Release Android lint | 0 errors, 19 warnings |
| APK signature and alignment | Passed; v3 signature; certificate matches the retained 1.1 APK |
| Packaged identity | `nl.privaterecorder`, versionCode 3, versionName 1.2 |
| Packaged debuggable flag | Absent |
| APK size | 8,513,495 bytes |
| Android folder-picker/provider integration | Not yet tested on the handset |
| Native Google Drive/OneDrive connection | Neither provider connected or validated on the handset |

APK: `dist/PrivateRecorder-1.2.apk`. SHA-256:

```text
6b6d455b86650144133558c29bc02045a099805b4658d409c8328d3e27a590b9
```

Folder-export tests cover initial and repeat copies, read-back hash failures, preserving conflicting names, duplicate/case-colliding names, interrupted staging writes and retries, source changes, and a destination appearing during a copy. A completed run writes a new manifest for fully verified bundles. The release review also moved manifest I/O failures outside recoverable per-bundle handling, so a partial manifest is not published as successful. Tests use a simulated document provider; they do not establish actual provider behavior, available storage, or handset lifecycle reliability. Keep the export screen open, use a provider supporting rename, and avoid concurrent writers in the destination folder. Conflict checks cannot provide atomic no-replace behavior for arbitrary third-party providers.

Folder export preserves separate committed audio, sidecars, and immutable event deltas/checkpoints. It is not the ZIP snapshot format: it omits the complete root `events.jsonl` and ZIP `export_metadata.json`. Existing ZIP export remains available and is still the input to `tools/verify_export.py` and `tools/organize_export.py`.

The desktop organizer verifies a ZIP before creating separate dated copies, preserves original member bytes, and produces indexes plus hashes. Its result is distinct from an Android cloud connection. Desktop Google Drive/OneDrive copy verification is separate from this APK validation; no completed cloud-copy totals or native cloud success are claimed here.

Installation must be coordinated around the active recording: finish and save the current segment, preserve verified exports, update over the same application without uninstalling, then explicitly restart and check recording. The new APK uses the existing signing key, but replacing an app can still interrupt its process.

## Version 1.1 — installed recording and two-provider backup code

Performed on 2026-09-25 in the Windows development workspace.

| Check | Result |
| --- | --- |
| Signed release build, unit tests, and lint (`package.ps1`) | Passed |
| Java unit tests | 49 passed, zero failures/errors |
| Python export verifier tests | 25 passed |
| Release Android lint | 0 errors, 14 warnings |
| APK signature and alignment | Passed, persistent release certificate, v3 signature |
| Packaged identity | `nl.privaterecorder`, versionCode 2, versionName 1.1 |
| Packaged debuggable flag | Absent |
| Internet permission | Present for direct Google Drive/OneDrive transfer |
| Google setup | Android release OAuth client created; Drive API enabled; `drive.file` declared. Audience still Testing; production branding incomplete. |
| Microsoft setup | Registration blocked by unresolved Azure/Entra MFA; no client ID configured |
| Physical phone connection and installation | Poco X7 Pro authorized over USB/ADB; signed 1.1 APK installed successfully |
| Phone permissions | Microphone and notification permissions granted |
| Initial recording observation | App UI showed Recording and initial saved bytes |
| Short screen-off check | Passed: display Dozing; foreground microphone service, RECORD_AUDIO access, and partial wake lock present; audio capture active=true and silenced=false |
| Active microphone configuration | Android reported MIC input, 48 kHz, mono, PCM 16-bit capture for `nl.privaterecorder` |
| Android idle exemption | Package present in the user idle whitelist |
| Playback, audible content and segment boundaries | Not verified |
| Extended recording trial, interruptions, thermal/power behavior | Not yet performed |
| Live cloud connection and uploads | Neither provider connected; phone recording is currently local-only and cloud setup is deferred |

APK: `dist/PrivateRecorder-1.1.apk`.

```text
1ff667a2c594789542f857030a1f693933ca26bb269e48a512d2d9c4270091f4
```

The automated tests cover audio framing, evidence integrity, damaged-bundle isolation, journal snapshots, export manifests, durable cloud receipts, simulated upload failures/retries, remote hash verification, token invalidation, and authentication helpers. They do not prove actual account authorization, Android lifecycle behavior, audible audio, or uninterrupted capture. The final worker uses isolated snapshots and checks all recorded bundle integrity references before upload and before acknowledging it.

The short screen-off check passed at the Android service/audio-state level: microphone capture remained active and was not reported silenced while the display was dozing. The reported PCM format describes the capture input, not the AAC output file. These observations do not establish audible recordings, successful finalized files, uninterrupted capture, or long-term operation. Playback and the longer handset trial remain outstanding.

The build includes native backup code but neither provider is connected or validated end to end on the Poco. Google Testing grants expire after seven days for the requested file access; production publishing must be completed for ongoing use. Google branding publication remains blocked on the staged information site's hosting/terms step, and Microsoft registration remains blocked on Azure/Entra MFA. See [CLOUD_SETUP.md](CLOUD_SETUP.md) for setup status and required handset tests.

## Historical version 1.0 — first APK

Performed on 2026-09-25 in the Windows development workspace.

| Check | Result |
| --- | --- |
| Debug and release Android build | Passed |
| Java unit tests | 7 passed: ADTS framing/invalid lengths, path/identifier validation, SHA-256 snapshot/truncation behavior |
| Python export verifier tests | 15 passed: tampering, missing/extra entries, unsafe/aliased paths, duplicate entries, bad metadata and truncated archives |
| Android lint, debug and release | 0 errors, 8 warnings |
| APK signature verification | Passed, v3 signature |
| APK alignment | Passed |
| Packaged application ID | `nl.privaterecorder` |
| Packaged debuggable flag | Absent (release APK) |
| Packaged Internet permission | Absent |
| Connected physical Android devices | None |
| Physical microphone/playback, screen-off, interruptions, thermal/power tests | Not performed |
| 24–72 hour Poco trial or year-long recording | Not performed |

The lint warnings concern deliberate synchronous preference commits, the foreground capture wake lock without a time limit, and English UI strings. The wake lock is released on completion/destruction. Static analysis does not validate OEM behavior.

The APK is `dist/PrivateRecorder-1.0.apk`. SHA-256 at this validation:

```text
511513dd95b0f2086dadbcd5aba1f12bb69d9fa58a156e346c0b7d0afaeb50ea
```

Rebuilding can change the APK digest. `package.ps1` refreshes `dist/SHA256SUMS`.

Remaining reliability limits: Android may silence or stop microphone capture; a process kill prevents its own final event from being written; storage I/O and hashing can delay the capture loop despite a ten-second microphone buffer; the AAC writer expects one complete access unit per encoder output buffer and explicitly rejects partial frames. Validate playback and every segment boundary on the actual device. File hashes do not test audibility, establish authenticity, or prove legal admissibility.

Version 1.0 was local-only with manual ZIP export. Version 1.1 added native Google Drive and OneDrive backup code. Version 1.2 adds manual individual-file folder export; handset installation and validation of that option remain outstanding. Remote listening and over-the-air APK updates are not implemented.
