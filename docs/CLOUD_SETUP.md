# Google Drive and OneDrive setup

Version 1.1 sends completed recordings directly from the phone to the connected cloud accounts. The PC is not an upload relay. Neither provider is enabled until you connect it in the app; recording itself works without a network.

## Current setup status

As of this development handoff on **2026-09-25**:

- In Google Cloud project **`private-recorder-509713`**, the project policy was accepted, OAuth branding and an Android release OAuth client were created, the Drive API was enabled, and `drive.file` was declared. The owner account is an approved test user. The OAuth audience is still **Testing** because the Console requires completed branding before publishing. Generic app-information/privacy pages are staged in `docs/public-site/`; Firebase hosting setup awaits acceptance of its terms. Successful phone authorization/upload has not been confirmed.
- The Microsoft application has not been registered. Azure/Entra offers only Authenticator approval/code, and the user has only the personal Hotmail Authenticator entry available. MFA access remains unresolved, so no working application/client ID has been confirmed.
- The signed 1.1 APK was successfully installed on the physical Poco X7 Pro via USB/ADB. Microphone and notification permissions were granted. The app showed **Recording** and initial saved bytes. The short screen-off check passed: Android reported active, unsilenced microphone capture, the foreground service, and a partial wake lock while the display was dozing. The app was present in Android's user idle whitelist.
- **Neither cloud provider is connected. Recording is currently local-only, and cloud setup is deferred.** Phone authorization and uploads to both providers remain untested, as do audible playback and a long recording trial. Automated protocol tests use simulated servers and do not establish successful live account transfers or continuous capture.

Use [VALIDATION.md](VALIDATION.md) for build/package results and subsequent verified progress. Do not treat the existence of the APK or these instructions as proof that cloud backup is already active.

## Connect on the phone

1. Install the signed `PrivateRecorder-1.1.apk` using the same signing key as any existing installation. Stop and preserve recordings before an update; do not uninstall an existing recording archive to update it.
2. Open **Private Recorder → Google Drive & OneDrive backups**.
3. After completing the Google registration below, tap **Connect Google Drive**, select the intended account, and approve the requested file access.
4. After completing the Microsoft registration below, open **Microsoft app registration**, enter its public **Application (client) ID**, and save. Finish the browser sign-in for the intended OneDrive account. You can use **Connect OneDrive** to start again if needed.
5. Leave **Back up only on unmetered networks (usually Wi-Fi)** off to allow Wi-Fi or mobile data, or enable it to wait for a network Android classifies as unmetered. Off is the default.
6. Make a short disposable recording and stop it to close the audio file. Tap **Sync saved files now**. Review Google Drive and OneDrive status separately, then use each **Open backup folder** button.

Both account connections enable ongoing backup for their own provider. A successful sign-in is not a successful upload: a folder link and verified-upload status appear only after cloud requests succeed. Background jobs are subject to Android scheduling and network availability.

## Google registration

Use the Google Cloud project intended for this app. The project currently being prepared is `private-recorder-509713`.

1. Enable the **Google Drive API** for that project.
2. Complete the OAuth consent configuration for the intended account/audience. If the project is in testing, make sure the intended recording account can authorize it under the project's current test-user rules.
3. Create an **Android** OAuth client using the following exact package and release certificate fingerprint:

| Field | Value |
| --- | --- |
| Android package | `nl.privaterecorder` |
| Release certificate SHA-1 | `C4:97:20:0E:B9:47:15:08:D9:B3:D5:4C:DB:F7:F4:E0:A3:6C:C2:E3` |
| Requested scope | `https://www.googleapis.com/auth/drive.file` |
| Created Android client ID | `541038762280-44v78kic8eh71sbk1fp8s25edkpu0lor.apps.googleusercontent.com` |

4. Install the APK signed with that certificate and connect Google Drive on the phone. The app uses Google Play services authorization; it does not ask you to paste a Google client secret, access token, or refresh token into the UI.

The requested `drive.file` scope limits access to files created or opened through this app. The recorder creates a folder named **Private Recorder Evidence** in My Drive and identifies its own folder with a private app property. A pre-existing unrelated folder with the same visible name does not automatically become the app's backup destination. Each immutable recording/event bundle gets its own subfolder.

If the installed app reports that Google sign-in is not configured, check the project, Drive API, package, signing certificate, and consent configuration. The debug APK normally has a different signing certificate; its successful installation does not mean the release OAuth registration matches it.

## Microsoft registration

The app needs a public/native application registration in Microsoft Entra. Complete the required account sign-in and MFA in Microsoft's own interface.

1. Register an application, for example **Private Recorder**, with **Accounts in any organizational directory and personal Microsoft accounts** as the supported account audience. This matches the build's use of Microsoft's `common` authorization endpoint and permits either kind of OneDrive account.
2. Configure a mobile/desktop public-client redirect with this exact URI:

```text
nl.privaterecorder://oauth/microsoft
```

3. Configure delegated Microsoft Graph access to **`Files.ReadWrite.AppFolder`**. The app also requests **`offline_access`** so it can refresh authorization for background backup. Organizational policies may require an administrator's consent.
4. Copy the public **Application (client) ID** into **Microsoft app registration** in the recorder. It is a UUID. The app uses authorization code flow with PKCE; no client secret is needed or accepted.
5. Finish **Connect OneDrive** on the phone and verify a disposable upload.

The app obtains Microsoft's special **approot** folder and creates **Private Recorder Evidence** inside it. Its visible location is normally:

```text
OneDrive/Apps/<Microsoft application display name>/Private Recorder Evidence/<bundle>/
```

`Files.ReadWrite.AppFolder` limits the app to its application folder. Use the app's **Open backup folder** link to locate the actual destination; the application display name determines the intermediate folder name.

## What sync does and what “verified” means

Completed AAC files, committed metadata, opening metadata when present, and immutable snapshots of the event journal are eligible for backup. Audio still being recorded stays on the phone until its segment closes. Interrupted audio becomes eligible after the recorder preserves/finalizes it; a sudden device failure can still lose recent unwritten data.

Each provider has independent queued work and locally stored receipts. Uploads stream from local files over HTTPS. Before acknowledging a bundle, the app downloads every remote file and compares its complete SHA-256 hash and byte count with the local file, then checks that the local file has not changed. Existing matching files are verified and reused. Different contents or ambiguous names produce an error instead of an overwrite. One provider's outage does not intentionally stop the other provider or microphone capture.

A receipt means **the bytes matched at the recorded verification time**. Previously acknowledged files are not downloaded on every run. Later deletion or modification in the cloud may therefore go undetected by the app; the receipt is historical evidence of transfer, not a permanent guarantee of availability, authenticity, or legal admissibility. Periodically inspect and independently download your cloud copies, retain original exports, and arrange preservation with your lawyer.

The app never automatically deletes local recordings, even after both uploads verify. It does not delete cloud files when you disconnect, and it does not mirror cloud deletions onto the phone. Full cloud or local storage needs your attention; successful backup does not free phone space. Changing accounts/destinations gives a different receipt scope, so prior-account receipts are not accepted as proof of backup to the new account.

## Network, quota, and privacy

- With both providers connected, every new byte is normally uploaded twice and downloaded twice for verification. At about 0.7 GB/day of audio, budget roughly **2.8 GB/day of total network traffic**, plus metadata, protocol overhead, and retries. A backlog can cause a much larger catch-up transfer.
- Each cloud retains about 0.7 GB/day, or roughly 21 GB per 30 days, in addition to the phone's originals. These estimates depend on the actual encoder output. Check both providers' available quota and your mobile-data allowance.
- New completed segments request sync. A roughly 15-minute periodic job provides another opportunity; Android may delay jobs and retries. Force-stop, unavailable networks, sign-in expiry, full storage, or provider errors can leave a backlog. Reopen the app after a force-stop and review both statuses.
- The app stores sign-in state in private no-backup storage encrypted with a device-bound Android Keystore key. It uses HTTPS for transfers and does not send a provider's bearer token to a redirected download host. Clearing app data or losing access to the device key requires reconnecting accounts.
- Audio files, metadata, and ZIP exports do not receive an additional password-encryption layer from this app. Anyone who can access the connected cloud folder can access its contents. The app does not create public sharing links or share evidence with a lawyer automatically.

## Check the complete workflow before relying on it

Record recognizable speech, finish the segment, and verify that both cloud folders contain the expected files. Download copies from each provider on another device, compare their SHA-256 hashes to the originals/metadata, and listen to the audio. A correct hash of silence is still silence.

Repeat a disposable test with the screen off, network access removed and restored, and each provider disconnected/reconnected. Confirm that queued files eventually arrive without duplicate conflicting objects and that the chosen mobile/unmetered policy behaves as expected. Keep checking actual recordings and independent copies during the longer Poco trial described in the [README](../README.md).

These checks remain outstanding until performed on the physical phone and real accounts. Compiled code, protocol tests, or a successful folder-creation request alone do not establish dependable 24/7 recording and backup.

## Provider references

- [Google Drive upload formats](https://developers.google.com/workspace/drive/api/guides/manage-uploads)
- [Google Drive private app properties](https://developers.google.com/workspace/drive/api/guides/properties)
- [Microsoft application folders](https://learn.microsoft.com/en-us/graph/onedrive-sharepoint-appfolder)
- [Microsoft conflict handling for drive items](https://learn.microsoft.com/en-us/graph/api/resources/driveitem?view=graph-rest-1.0#instance-attributes)
- [Microsoft authenticated download redirects](https://learn.microsoft.com/en-us/graph/api/driveitem-get-content?view=graph-rest-1.0)
