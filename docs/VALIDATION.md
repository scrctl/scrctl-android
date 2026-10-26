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
fresh install and subsequent mirroring were not established by that initial run.
The unlocked SDK and GUI acceptance below supersedes this pending result.

## Remaining acceptance boundaries

- Other cancellation timings during signing or the final device-side commit;
  cancellation does not promise rollback of completed installation changes.
- Physical network loss/switch and foreground restoration during an active mirror.
- Other Android models, 16 KiB page devices, other ABIs and long sessions.
- Basic Home/volume/Lock controls require separate visible device confirmation.

CI builds APKs, runs JVM tests and lint, and collects corresponding sources.
It cannot replace real iPhone pairing, visual/input or DDI installation acceptance.

## NSD SDK integration (2026-10-27)

SDK revision: `f797fe3` (development snapshot based on v1.0.1). Debug APK,
Android-test APK, five JVM geometry tests and Android lint pass with JDK 17,
SDK 36 and NDK 29. The APK was installed over the existing App on the same Xiaomi
13 Ultra/API 36. The direct instrumentation runs used the existing App record.

All five instrumentation tests pass with hardware acceptance enabled. In addition
to the three initial tests, these cover a system-registered test peer reaching the
native SDK device list, warm discovery in under two seconds, lost-service eviction,
shared NSD leases and final-close cleanup. An opt-in test identifies the existing
paired iPhone14,4 / iOS 27.0.1 using NSD, opens a Wi-Fi session and receives three
advancing decoded frames before stopping video and closing normally.

The foreground App UI also discovered the paired iPhone and displayed its visible
lock screen in the fullscreen mirror. Backgrounding released the App UID's active
NSD discovery and service-info requests, verified through Android system diagnostics.
No pairing or DDI changes were needed in this round. A manual Refresh now reuses
the server/subscription instead of restarting both.

Native NSD is enabled on API 34+; lower releases retain the SDK's direct backend.
The pairing advertiser is unchanged because public Android registration cannot set
the exact SRV target. Physical network switching and simultaneous Ethernet/Wi-Fi
remain unverified; the automated network test exercises controller transitions.


### Device-test cleanup incident

A subsequent `connectedDebugAndroidTest` run passed all five tests, but the
Android Gradle plugin then uninstalled both target and test APKs by default,
removing the App's private data. The App was reinstalled. The original Android
record had no local backup; an existing Mac xdb record was copied directly into
App-private storage under the user's prior authorization to use it for Android
debugging. Its contents were not logged. The hardware test was rerun through
`adb shell am instrument`, which does not uninstall the App afterward.

`android.injected.androidTest.leaveApksInstalledAfterRun=true` is now set for the
pinned Android Gradle plugin. Prefer explicit `adb install -r` and `am instrument`
for a previously paired host; do not use clean/uninstall operations against its
private data. The NSD feature itself does not import or migrate credentials.

The user then completed a new Wi-Fi pairing through the App. The resulting
App-private record was checked to differ from the temporary Mac record, and the
App's list identified the newly paired iPhone using the active NSD subscription.
All five direct instrumentation tests passed again with this fresh Android
record (5.65 seconds), including the Wi-Fi connection and advancing video frames.
The temporary recovery copy was deleted. The installed APK's SHA-256 matched the
final local artifact, and direct instrumentation left the App/data installed.

## DDI session cleanup integration (2026-10-27)

SDK revision: `bb07cae8364f218cbb2ae3498fae133f8e06d4f5` (based on v1.0.1).
Debug and test APKs build, the five JVM geometry tests pass (unchanged test inputs
were reused by Gradle), and Android lint passes. This build used Homebrew OpenJDK
17; the installed GraalVM 17 failed at Android's JDK-image transform.

Both APKs were installed with `adb install -r` on the same Xiaomi 13 Ultra/API 36.
All six direct instrumentation tests passed, with no skips (10.086 seconds).
The new opt-in hardware test performed three successive `ddi.status` requests
through native NSD discovery and the App-owned pairing record. Each confirmed the
existing mount; measured times inside Android were 1.203, 1.258 and 1.414 seconds.
No query waited for the previous image-mounter session's 30-second expiry.
Existing tests also confirmed discovery lifecycle, cancellation and three
advancing decoded iPhone frames through an authenticated Wi-Fi connection.

The credential digest was identical before and after installation/tests, and the
installed APK hash matched the local artifact. The App was left installed and
relaunched after testing. No credentials were imported, replaced or saved outside
App storage; only a digest was retained for comparison. No DDI download, removal
or fresh installation was performed. This closes the
Android repeated-query acceptance for this device, not the fresh-install or
physical network-switch boundaries above.

## Fresh Android DDI installation and GUI recovery (2026-10-27)

SDK revision `bb07cae`, debug App on Xiaomi 13 Ultra/API 36, using the existing
App-private pairing with iPhone14,4 / iOS 27.0.1. No production App/SDK behavior
was changed for these checks. The new `DdiLifecycleTest` requires both an explicit
device identifier and `xdbDdiLifecycle=true`; ordinary test runs skip removal.
Its separate `xdbDdiInspect=true` case only queries state after an uncertain
installation, without retrying or changing it.

### SDK lifecycle test in the Android application sandbox

- Removed the existing DDI through the embedded SDK. Independent status confirmed
  both `mounted:false` and `cryptex_installed:false` before preparation.
- Created a unique, empty test cache. Interrupted the request after nonzero
  progress on the image download; the request failed rather than claiming success.
  Server shutdown joined cancellation cleanup. No staging directory or version
  lock remained, and independent status still confirmed absence.
- An initial attempt reached upload completion but timed out waiting for the
  final installation reply (120-second install budget). A read-only inspection
  confirmed the DDI was still absent; the test did not blindly replace an
  uncertain installation. The user then confirmed the iPhone was unlocked and
  would remain lit.
- The full lifecycle was rerun with another empty cache. Download cancellation
  and cleanup took 119 ms; retry still started empty, downloaded and verified the
  five assets, obtained the Apple ticket, uploaded, installed and verified
  `com.apple.MobileAsset.DDI` version `27.1.5228.8` in 17.285 seconds.
- Independent status confirmed installation and mount state. A new authenticated
  Wi-Fi session received three advancing decoded frames. The opt-in test passed
  in 30.198 seconds. This timing covers this run; it is not a general download or
  installation performance guarantee, nor proof that every timeout is lock-related.

### Real Android GUI

- With the App stopped, the Mac CLI removed the DDI and independently confirmed
  absence. The App's normal `cache/ddi` directory did not yet exist. Using only
  actual UI taps, selected the paired iPhone and pressed **Prepare DDI**. The
  screen displayed transfer progress and then **DDI prepared. You can now mirror
  this iPhone.** This was a fresh GUI download/install, not mounted-image reuse.
- For the GUI cancellation check, removed the DDI again while the App was stopped
  and moved that newly downloaded cache aside. Started **Prepare DDI** from the
  empty normal cache and tapped **Cancel** while transfer progress and the busy
  indicator were visible. The screen showed **Cancelled. Completed device
  changes are not rolled back.** and re-enabled Prepare/Connect.
- Pressed **Prepare DDI** again. The GUI reached the success message after about
  ten seconds of observation; that retry could reuse assets downloaded before
  cancellation. Pressing **Connect** opened the actual mirror, visually showing
  the iPhone's Developer Mode settings screen. A successfully opened mirror page
  alone was not counted as visible-frame evidence.

The original credential digest remained unchanged throughout. Only test-created
cache directories were removed; the verified normal App cache was retained.
Both APKs were installed with `adb install -r`, without clearing App data.
Build and Android lint passed. The DDI was left installed and mounted; these
results cover one Android/iPhone pair, not other OS versions or physical network
switching. Cancellation was exercised at download and GUI transfer boundaries,
not exhaustively at every device-side commit point.
