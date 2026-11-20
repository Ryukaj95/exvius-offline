# Source code

This folder contains the offline adaptation, local game logic and Android integration.

| Folder | Purpose |
| --- | --- |
| `server/` | Local game logic, campaign persistence and request handlers. |
| `exvius/` | Original-client integration, content preparation and release tools. |
| `memorial/android/` | Android download/bootstrap integration and embedded local runtime. |
| `harness/` | Protocol and content inspection utilities. |
| `docs/` | Supporting format and implementation documentation. |

## Building

The current release script runs on Windows.

The build uses Java 11, Python 3.10, Node.js, Android SDK platform 33 with build-tools 36.0.0, Gradle 7.6.4, Chaquopy 16.1.0, apktool 3.0.3 and Frida Gadget 17.24.3 for arm64.

Set `JAVA_HOME` and `ANDROID_HOME` for your machine. The integration expects the verified original Global v9.0.0 APK at `repository snapshots/FFBE-Global-v9.0.0-original.apk` and the external tools under `tools/vendor/`. Original client binaries and signing keys are not part of the source repository.

The original APK SHA-256 is `955deacfbe4b3d0cd04e2947eb005e310915ba98132d71216626bfb3554ad246`.

The Android runtime is built from `memorial/android/`. Then `exvius/android_build.py --local-engine --speed 1 --release-version 9.0.0-offline.2 --release-code 9002` prepares the integrated client. Package it with apktool, align it with zipalign, and sign it with your own private signing key. Keep the same key for future updates to your installation. Install APK updates manually from the same trusted publisher. Export your save before switching to an APK signed by a different publisher.

`exvius/build_distribution.ps1` runs these steps with `JAVA_HOME`, `ANDROID_HOME` and `EXVIUS_KEYSTORE_PASSWORD` set. Its `KeyStore` and `KeyAlias` parameters select your private signing key. Distribution builds disable Android debugging.

`exvius/build_release_data.py` packages the complete accepted game data from the established `data/` trees. It uses the same original/recovered/overlay precedence as `exvius/push_client_cache.py`, excludes campaign saves, and generates the release data manifest.

Original APK artwork, fonts and startup resources are in the downloaded `game-assets.apk` archive. `GameAssets` makes it available to Android's asset reader, and the original Cocos asset-path getter points to the same archive. The first-download screen does not need these files. Keep the data download complete before starting the original game activity.

For an existing data release, `exvius/build_release_data.py --add-client-assets --tag v0.1.1 --revision 2` adds this resource pack while reusing the unchanged large data parts.

For a new release, upload the new data parts and APK as release assets, then update `data/latest.json` with their exact URLs, sizes, checksums and increasing version numbers. Sign the manifest before publishing it for in-app data updates. Gameplay does not require a hosted game server.

### Signing game data updates

Without `EXVIUS_UPDATE_PUBLIC_KEY`, the build accepts only a byte-for-byte pinned copy of the revision 13 data manifest. This lets fresh installs download the existing data packs while a release key is being set up. Existing installed data remains available offline.

For ongoing releases, create a dedicated RSA release key outside the checkout. Use OpenSSL to export the public key and sign the final manifest:

```sh
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out /secure/exvius-release.pem
openssl pkey -in /secure/exvius-release.pem -pubout -outform DER | openssl base64 -A
python source/exvius/sign_data_manifest.py /secure/exvius-release.pem
```

Pass the base64 public key as `EXVIUS_UPDATE_PUBLIC_KEY` in the environment before building the Android runtime. Keep the private key offline and outside Git. Publish the generated `data/latest.signed.json` to this fork's `main` before distributing the keyed APK. Whenever pack metadata changes, increment the revision and regenerate the signed file. Clients with a configured key reject unsigned metadata, including the legacy copied `latest.json`. To rotate the key, distribute a new APK with the replacement public key.
