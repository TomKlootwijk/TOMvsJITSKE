# Building and preserving updates

On Windows, run `./setup-tools.ps1` once to fetch the pinned, verified local JDK,
Gradle and Android SDK. Run `./build.ps1` for the debug build, JVM tests and lint.
Run `./package.ps1` to build, test, check and sign a non-debuggable release APK.
The current distributable is `dist/PrivateRecorder-1.2.apk`; `dist/SHA256SUMS` records its
SHA-256 checksum. Packaging verifies the signature, alignment, application ID, version
and that the APK is not debuggable. Neither script installs anything on a phone.

The first packaging run creates a persistent release key and a random password
inside `.signing`. Both are excluded from Git. **Keep a secure backup of the
entire `.signing` folder, including `password.txt`.** Keep it private. Passwords
are passed to signing tools through the password file and are not printed.

Updates must use this same key and the same application ID. Increase
`versionCode` in `app/build.gradle` for a new release and retain the signing
folder when building it. Install the update over the existing app: uninstalling
or clearing its data deletes its private recording archive. Losing the signing
key means a replacement build cannot update the installed app normally. Export
and separately back up recordings before any installation or device maintenance.

The release signing key identifies this app's updates; it does not authenticate
the speaker or certify recording timestamps. The phone's recording integrity
records are separate from APK signing.

Google's Android OAuth registration must match both the package
`nl.privaterecorder` and the certificate used to sign the installed APK. The
current release certificate SHA-1 is:

```text
C4:97:20:0E:B9:47:15:08:D9:B3:D5:4C:DB:F7:F4:E0:A3:6C:C2:E3
```

This public certificate fingerprint is not the private signing key. A debug
APK normally has a different certificate and needs its own matching Android
OAuth registration. See [cloud setup](docs/CLOUD_SETUP.md) for the Google and
Microsoft registration requirements. A successful APK build or signature
verification does not establish that either cloud registration or handset
sign-in is working.
