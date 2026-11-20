# Carry

the phone, copied home.

[![version 1.1.0](https://img.shields.io/badge/version-1.1.0-5CE1FF?style=flat-square)](https://github.com/damianraine/simple_android_media_backup/releases/download/continuous/carry.apk)
[![download carry.apk](https://img.shields.io/badge/download-carry.apk-F2C14E?style=flat-square)](https://github.com/damianraine/simple_android_media_backup/releases/download/continuous/carry.apk)
[![license Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-F6F3EA?style=flat-square)](LICENSE)
[![Android 6.0+](https://img.shields.io/badge/Android-6.0%2B-7DFFB3?style=flat-square)](#requirements)
[![Kotlin 2.4](https://img.shields.io/badge/Kotlin-2.4-9AABC0?style=flat-square)](#build-it-yourself)

A small Android app that copies photos and videos to a drive on your own network. SMB, once in the evening and once at midnight. No account with us.

## Download

The installable build is published by GitHub Actions. It is not stored in git.

**[Download carry.apk](https://github.com/damianraine/simple_android_media_backup/releases/download/continuous/carry.apk)**

That address stays put. Every push to `main` rebuilds the file and replaces it on the prerelease tag `continuous`. A tag such as `v1.0.0` also publishes a numbered release, marked latest, with the same asset name.

The APK is signed with the public sideload key in `app/carry-sideload.jks` (password `carry-sideload`). It is not a Play Store signature. The key does not change between builds, so an update installs over the copy you already have. Android refuses that only when the installed app was signed with a different key. Carry opens uninstall in that case. Uninstall once, then install this file. Later updates install over the top. If the link answers 404, the workflow is still running, or the repository is still private.

```bash
adb install -r carry.apk
```

On the phone: **Settings**, then **Update Carry**. Carry reads `versionCode` from the release notes and downloads that same `carry.apk` when the published build is newer.

Bump `appVersion` and `appVersionCode` in `gradle/libs.versions.toml` before a build you want phones to take. The workflow writes both into the release notes. If the code does not go up, Carry says it is already the latest and leaves the installed copy alone.

## Requirements

| | |
| --- | --- |
| Device | An Android phone. `minSdk` 23 (Android 6.0). |
| Drive | An SMB share on the same Wi-Fi. A NAS, a computer sharing a folder, Samba. |
| Photos | Permission to read photos and videos. |

## What you can do

**First launch.** With nothing saved, Carry says **Searching for destinations...** and knocks on port 445 across the local /24. Tap a drive. Carry asks **Do you want to add this as a saved destination?** If the drive wants a password, you type it. Then you pick a folder, such as `media/backup/phone`. **Find a folder** searches the share, and you can open a path you type. The host, the folder, and the password stay on the device.

The password is encrypted with the Android keystore. `allowBackup` is off. An update signed with the same key keeps the saved destinations.

**Settings.** Look for destinations again, change the folder of one you already saved, or choose which saved destination is in use. **Copy times** is when the phone copies. It starts at 00:00 and 18:00. Add a time, or remove one. With none left, only **Sync now** runs. **Start from date** skips anything older. **Year / month** or **Source folder** is how the copies are laid out. Camera, DCIM, Pictures, WhatsApp media, Downloads, and other photo and video folders are included. Caches, thumbnails, and WhatsApp statuses are not.

**The copy.** Before anything is sent, Carry lists the folders it would write and skips a file that is already there with the same size. It does not overwrite. A different file that would use the same name is saved beside it as `name (2).jpg`. **Sync now** on the main screen runs a copy immediately. Otherwise Carry wakes at the times saved in Settings, and waits for Wi-Fi. A six-hour backstop catches a slot the alarm slept through, and does nothing if that slot already finished.

## What stays on the device

Passwords are stored in the Android keystore. They are not written into logs. Clearing a destination forgets its password. The photos themselves are not kept by Carry. They stay in the gallery, and a copy lands on the drive.

## Build it yourself

JDK 21, the Android SDK, and the wrapper in this repo.

```bash
./gradlew :core:test :app:assembleRelease
```

The release APK is `app/build/outputs/apk/release/carry.apk`. `exportApk` also copies it to `dist/carry.apk`. `compileSdk` is 37 (the installed platform is `android-37.2`). `targetSdk` is 36. Kotlin 2.4.

GitHub Actions runs that on every push and pull request. Pushes to `main`, and tags named `v*`, also publish `carry.apk`. Pull requests only upload an Actions artifact.

## Versions

| | |
| --- | --- |
| App | 1.1.0 (versionCode 2) |
| Sideload URL | [continuous/carry.apk](https://github.com/damianraine/simple_android_media_backup/releases/download/continuous/carry.apk) |
| Numbered releases | Tag `v*` on `main`. The asset name is still `carry.apk`. |

## No warranty

Carry is offered as it is. There is no warranty of any kind, express or implied, including any warranty that it works, that it will keep working, that it is fit for a particular phone, or that it will not miss a photo.

It is a side project that talks to an SMB share on a network the author does not control. Your drive, your password, your files. If it fails to sign in, skips something, or does something surprising, that is on the software, and the remedy is to uninstall it.

The continuous APK is whatever `main` last built. It is not a finished product, and it is signed with the public sideload key rather than a shop signature. Do not install it if you need something you can rely on.

The legal wording is the warranty disclaimer in the [Apache-2.0 licence](LICENSE).

## Licence

Code is [Apache-2.0](LICENSE). SMB is [smbj](https://github.com/hierynomus/smbj), also Apache-2.0.
