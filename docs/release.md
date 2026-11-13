# Releasing the Android app

All steps run on BookahPC. Chris's explicit yes is needed for every push and every release.

## One command

```text
powershell -NoProfile -ExecutionPolicy Bypass -File D:\Projects\BookahPhotos\tools\release-android.ps1 -Version 0.8.0
```

Without `-Publish` it only builds and checks, so it is safe to run at any time:

1. unit tests of the modules,
2. release build with the version (`versionCode = major*10000 + minor*100 + patch`),
3. the APK's certificate must equal `android\release-signing-cert.sha256` (otherwise it stops),
4. writes `BookahPhotos-<version>.apk` and its `.sha256` in `android\app\build\outputs\apk\release\`.

To publish (after Chris has said yes): add `-Publish -ChrisSaidYes`. It pushes the commits and creates the GitHub pre-release `v<version>` on the private repository `BookahCoding/BookahPhotos` with the APK attached. The release notes carry the certificate fingerprint.

## Version numbers

`0.1.0` to `0.8.1` were the test builds A to H (and `0.9.0` is Phase 5a, search on the phone); `0.1.0` to `0.7.0` were the test builds A to G (sign-in, Library, viewer, Collections, phone photos, delete, thumbnails from originals). The first public release will be `1.0.0`. Tags only ever go up, so a new APK installs over the old one.

## The signing key

- The key is `C:\BookahPC\bookahphotos\signing\bookahphotos-release.p12`, with its password in `keystore.properties.backup` in the same folder. `android\keystore.properties` points at it. Neither is in Git.
- **Back the folder up outside BookahPC.** Without the key a phone cannot update the app in place (it would have to be uninstalled first, losing the app's data).
- The certificate's SHA-256 is public and committed: `android\release-signing-cert.sha256`. Anyone can compare it with `apksigner verify --print-certs`.

## Updates inside the app

The app can ask GitHub for a newer version (Settings, Updates). It is **off** while the repository is private, because the check uses GitHub's public releases list without a password, as the other Bookah apps do, and a private repository cannot be read that way. To switch it on: make the repository public (Chris's decision), then build with `-PreleasesRepo=BookahCoding/BookahPhotos`. The app then offers "Check for updates" and a "Download" button that opens the APK; Android asks the reader to confirm the install.

## Why not GitHub Actions

The Hikyaku project builds releases in GitHub Actions with the key stored as a repository secret. For BookahPhotos the key stays on BookahPC only (a private family photo app, one maintainer), so the script above does the same job without putting a signing key into GitHub. If Chris wants Actions later, the Hikyaku `release.yml` is the pattern to copy.
