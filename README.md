# XAPK Installer

An Android app that installs `.xapk`, `.apks` and `.apk` files, similar to APKMirror Installer. Tap a file in your file manager or downloads list, pick **XAPK Installer**, and tap **Install**.

## Download

Get the latest APK from [Releases](../../releases/latest).

## First-time setup on your phone

The first install asks for these, and the app takes you to each settings screen when it needs it:

1. **Install unknown apps**: lets it install other apps.
2. **All files access**: only asked for when a file includes game data (OBB), which has to be copied into `Android/obb/`.

To make it the default for `.xapk` files, tap one, choose XAPK Installer, and pick **Always**.

## What it handles

- XAPK (APKPure-style, with `manifest.json`, split APKs and OBB data)
- APKS / SAI bundles (a zip of split APKs)
- Plain APKs
- Drops ABI splits that don't match your phone's processor, so mixed bundles install cleanly
- On Android 12+, updates of apps it installed skip the extra confirm screen

Encrypted `.apkm` files from APKMirror can't be installed. Only APKMirror's own app can open those.

## Updates

Every build is signed with the same permanent key (`app/signing.keystore`), so new versions install over old ones. To release a new version, raise `versionCode` and `versionName` in `app/build.gradle.kts` and push to `main`. GitHub Actions builds the APK and publishes the release.

Because the key is in this public repo, anyone could sign an APK that Android would accept as an update to this app. That's fine for a personal tool. For wider distribution, move the key to repo secrets.
