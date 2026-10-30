# Android validation status

Current SDK pin: **XDeviceBridge 1.4.0**, commit
`2ac3ae68335280aa80c1ab084a46e19da029fa10`.
App ABI: arm64-v8a. This App primarily validates embedding XDeviceBridge in an
Android application. Results below cover development/debug APKs, not a release
or store-distributed build.

This document summarizes completed acceptance and remaining boundaries, rather
than intermediate debugging attempts. Hardware results retain their tested SDK
revision: they are not a claim that every scenario was rerun on the current pin.

## Build and automated checks

- JDK 17, Android SDK 36 and NDK 29.0.14206865 build the debug APK and test APK.
  JVM geometry tests and Android lint have passed locally.
- The five geometry tests cover aspect/padding, rotations, inverse touch mapping,
  invalid geometry and dragging outside the visible content.
- [CI for App commit b0da699](https://github.com/scrctl/scrctl-android/actions/runs/40878990336)
  passed with the current SDK pin. It builds the debug APK, runs JVM tests and
  lint, and packages matching sources. It does not run iPhone hardware acceptance.
- On-device instrumentation covers independent embedded Server ports, SDK version,
  request interruption/close races and simulated foreground/network transitions.
  These are physical Android instrumentation tests, not emulator results or proof
  of physical Wi-Fi switching.

## Real-device acceptance

Hardware coverage uses a Xiaomi 13 Ultra (Android API 36, 4 KiB pages) and
an iPhone14,4 running iOS 27.0.1 over Wi-Fi.

| Capability | Confirmed result | Evidence scope |
| --- | --- | --- |
| Pairing and mirror | The user completed Wi-Fi pairing, connection and visible mirroring, and confirmed touch input. A new App-owned pairing also passed discovery and advancing-frame checks. | Initial SDK and NSD integration, 2026-10-27 |
| Native discovery | System-registered peers reach the SDK list; warm discovery, lost-service eviction, shared leases and final-close cleanup pass. The paired iPhone produces three advancing decoded frames. | NSD revision `f797fe3`; repeated on SDK 1.1.0 |
| Discovery cleanup | Backgrounding releases the App UID's active NSD discovery and service-info requests, confirmed through Android system diagnostics. | NSD integration, 2026-10-27 |
| DDI status | Three successive authenticated status queries confirm the mount, each within the test's ten-second limit. | Revision `bb07cae`; repeated on SDK 1.1.0 |
| Fresh DDI preparation | Empty-cache download, verification, Apple ticket acquisition, installation and mount verification pass; a new session receives advancing video frames. | Revision `bb07cae`, 2026-10-27; details below |
| Pairing brand | The real App progress and macOS DNS-SD observation both show `scrctl - Xiaomi 13 Ultra`, with the expected Apple service type and TXT values. | Revision `9d607ee`; advertisement and cancellation only, not a new completed pairing |
| Multi-touch | Five simultaneous contacts, movement, individual release and final zero-contact state reach the iPhone. A two-finger CANCEL also returns to zero. | Revision `09a03d3`, 2026-10-29; automated actual View-to-device test |

Native NSD is enabled on API 34+; earlier versions or unavailable native discovery
use direct mDNS. Pairing advertisements retain the SDK implementation because the
public Android registration API cannot set the exact SRV target. Acceptance of
native NSD does not establish the fallback on every supported Android version.

### DDI download, installation and cancellation

The opt-in Android `DdiLifecycleTest` used the existing App-private pairing and a
separate empty cache. It removed the DDI and independently confirmed both
`mounted:false` and `cryptex_installed:false` before preparation.

Cancellation after nonzero download progress completed cleanup without leaving a
staging directory or version lock; independent status still confirmed absence.
A retry starting from an empty cache downloaded and verified all five assets,
obtained the Apple ticket, uploaded and installed
`com.apple.MobileAsset.DDI` version `27.1.5228.8`. Independent status confirmed
installation and mount state, followed by three advancing decoded video frames.
HTTPS verification remained enabled using the App's Android system trust roots.

Separate actual GUI testing also started with an absent DDI and empty normal
App cache. Prepare DDI displayed transfer progress and then
**DDI prepared. You can now mirror this iPhone.**
A further GUI transfer cancellation displayed
**Cancelled. Completed device changes are not rolled back.** and re-enabled the
controls. Retrying succeeded, and Connect visibly displayed the iPhone screen.
That GUI retry could reuse assets downloaded before cancellation.

The existing pairing-record digest stayed unchanged. These checks establish
fresh preparation and cancellation/retry on this device pair; they do not prove
rollback or cancellation at every signing/device-commit stage. DDI removal is
explicitly opt-in; ordinary test runs skip it. See the
[README test instructions](../README.md#build).

### Multi-touch and SDK update coverage

`MultitouchTest` launched the actual Activity, selected the paired iPhone and
connected its mirror. It dispatched multi-pointer MotionEvents through
MirrorView, using pointer IDs different from XDB slots. An iPhone Safari observer
confirmed the resulting contacts. This is automated View-to-device acceptance,
not manual five-finger interaction on the Android glass.

The three embedded-runtime instrumentation cases also passed with the 1.4.0
development revision `09a03d3`, including the final input-release TCP drain fix.
Earlier SDK 1.1.0 checks passed all six embedded-runtime/NSD cases; SDK 1.3.0
passed the three embedded-runtime cases. The final 1.4.0 pin has CI coverage as
linked above; no additional full hardware rerun is claimed for that pin.

## Preview refresh without a fixed client cap

The preview loop no longer sleeps to fill a fixed 50 ms interval. The debug APK
built and was installed with `adb install -r` on the existing Xiaomi 13 Ultra;
its saved pairing connected to the iPhone and the actual fullscreen mirror
showed advancing content. Two sampled screen images differed within the mirrored
content area. The phone's `dumpsys gfxinfo` counter reported zero, so no measured
preview FPS or power improvement is claimed. This check retained the pinned
1.4.0 SDK and software decoding; it does not validate MediaCodec or the newer
Server's optional `max_fps` API.

## Remaining acceptance boundaries

- Physical network loss/switch and foreground restoration during an active mirror;
  simultaneous Ethernet/Wi-Fi, IPv6 scope and multiple-interface behavior.
- Visible device confirmation of the Android UI's Home, volume and Lock controls.
- Cancellation during signing or the final device-side commit. Cancellation does
  not promise rollback of completed installation changes.
- Other Android/iPhone models and OS versions, 16 KiB page devices, other ABIs and
  long sessions.
- Release signing, distribution and installation of a release APK are outside
  this debug-App acceptance.

## Preserve device state when testing

Use `adb install -r` and direct `adb shell am instrument` for an already paired
App. The project also sets
`android.injected.androidTest.leaveApksInstalledAfterRun=true` for its pinned
Android Gradle plugin. Do not clear App data or uninstall it as routine test
cleanup. Pairing records remain SDK-owned in App-private persistent storage.
Destructive DDI lifecycle tests require explicit opt-in; read-only status checks
should resolve uncertain device state before retrying an installation.
