# XAPK Installer

A small Android app that installs `.xapk` files (an APK bundle with optional split APKs and OBB data files).

## Build

**Option A: GitHub Actions (no local setup).** Push this folder to a GitHub repo. The workflow in `.github/workflows/build.yml` builds a debug APK. Download it from the run's **Artifacts** section.

**Option B: Android Studio.** Open the folder, let Gradle sync, then Build > Build APK(s).

## Install on your phone

1. Install the APK. Android will ask you to allow installs from this app (Settings > Apps > XAPK Installer > Install unknown apps).
2. Open the app and tap **Choose .xapk file**, or open a `.xapk` in your file manager and pick XAPK Installer.
3. If the XAPK has data files (OBB), choose the `Android/obb` folder when asked. Android 11+ may not let you select it on every device, in which case the OBB step will fail and the APKs will still install.
4. Confirm the install prompt from Android.

## How it works

- An `.xapk` is a ZIP. `manifest.json` lists the package name and the APK files.
- All APKs go into a single `PackageInstaller` session, so split APKs install together.
- OBB files are copied to `Android/obb/<package>/` through the Storage Access Framework.

## Known limits

- Android only installs APKs signed the same way as an existing install when updating. A signature mismatch will fail with a message from Android.
- Some providers add a `.bin` extension to OBB files created with `application/octet-stream`. If a game can't find its data, check the file name in `Android/obb/<package>/`.
- Untested on a device: this code was written without a local Android SDK, so expect to fix compile errors on the first build.
- Change `com.example.xapkinstaller` to your own package name in `app/build.gradle.kts` and the Java/Kotlin package folders.
