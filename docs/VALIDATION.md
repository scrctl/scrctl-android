# Initial Android validation

SDK: XDeviceBridge v1.0.1, commit `6248da9f5a6ccdca345f45858f4a0c3fe8ef5a76`.
App ABI: arm64-v8a. These results cover a development/debug APK, not a released
or store-distributed build.

## Build and automated checks

- macOS / JDK 17 / Android SDK 36 / NDK 29.0.14206865: debug APK, test APK,
  JVM tests and Android lint build successfully.
- Five JVM geometry tests pass: aspect/padding, rotations, inverse touch mapping,
  invalid geometry, and dragging outside the visible content.
- Three instrumentation tests pass on a Xiaomi 13 Ultra (Android API 36,
  4 KiB pages): independent embedded Server ports and SDK version; request
  interruption/cleanup; repeated foreground/background and network-state cleanup.
- The network-state test invokes controller transitions; it does not constitute
  physical Wi-Fi switching acceptance. No emulator acceptance is claimed.

## User-confirmed real-device acceptance

Using the installed Android App and an iPhone over Wi-Fi, the user completed
pairing, connection and video mirroring, and reported working touch input.
The user also invoked Prepare DDI and observed
“DDI prepared. You can now mirror this iPhone.”

This confirms ordinary DDI preparation/check/reuse on this already prepared
phone. It does not prove a fresh download from an empty cache, first installation,
replacement or cancellation midway through a fresh installation.

## Fresh-cache DDI test

The Mac removed com.apple.MobileAsset.DDI version 27.1.5228.8 and confirmed
both installed Cryptex and mounted-image lists were empty. Android cache/ddi was
empty before preparation. The first attempt exposed an OpenSSL CA-store mismatch.
After the App integration supplied Android system trust roots explicitly, the
pinned SDK downloaded the DDI assets (about 16 MiB) with HTTPS verification enabled.
Installation then failed while waiting for its final device reply. A successful
fresh install and subsequent mirroring are not yet claimed; follow-up is pending.

## Remaining acceptance boundaries

- Fresh-cache DDI download/install and cancellation on a suitable device.
- Physical network loss/switch and foreground restoration during an active mirror.
- Other Android models, 16 KiB page devices, other ABIs and long sessions.
- Basic Home/volume/Lock controls require separate visible device confirmation.

CI builds APKs, runs JVM tests and lint, and collects corresponding sources.
It cannot replace real iPhone pairing, visual/input or DDI installation acceptance.
