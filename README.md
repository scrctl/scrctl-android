# scrctl-android

A focused native Kotlin / Material 3 Android frontend for iPhone Wi-Fi mirroring
and control, and an application-sandbox validation host for XDeviceBridge.

The SDK is pinned to **XDeviceBridge v1.0.1**, commit
`6248da9f5a6ccdca345f45858f4a0c3fe8ef5a76`.
The App embeds libxdb_server and libxdb_client through JNI. It never executes an
external xdb program. Pairing records belong to xdb in App-private persistent
storage; the App does not parse credentials. DDI uses App cache storage.

The initial discovery-to-mirroring implementation is available. Build checks,
automated tests and real-device acceptance are recorded separately in
[docs/VALIDATION.md](docs/VALIDATION.md).

## Initial scope

- Wi-Fi discovery, pairing PIN/progress/cancellation and DDI preparation.
- Software-decoded video with aspect ratio, rotation and mapped single touch.
- Home, volume and side-button controls.
- Foreground-only sessions; network changes and backgrounding cancel native work.

v1.0.1 has no public branding configuration. Pairing retains its existing
`xdb - <Android model>` display name and Apple DNS-SD service types.
NsdManager discovery/advertisement is deferred; the initial App uses the SDK's
existing mDNS implementation.

## Build

Use JDK 17, Android SDK 36, NDK 29.0.14206865, Python 3, CMake and Ninja on macOS
or Linux. Install the Android command-line tools and accept the SDK licenses.

```sh
git clone --recurse-submodules https://github.com/scrctl/scrctl-android.git
cd scrctl-android
export ANDROID_HOME=/absolute/path/to/Android/sdk
# First build downloads and compiles pinned native dependencies.
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Select an iPhone from the initial list, prepare DDI if necessary, then Connect.
The mirror is fullscreen; Android Back returns to the list. Its small control menu
provides Home, volume and Lock. For first-time Wi-Fi pairing, select Wi-Fi pair,
then choose the Android host on the iPhone's Developer Mode page and enter the
transient PIN displayed by the App. Credentials remain in App-private storage.

[Architecture and lifecycle](docs/ARCHITECTURE.md) · [Validation](docs/VALIDATION.md)

## License

Apache-2.0 for the App; XDeviceBridge remains LGPL-3.0-only. Native and Android
libraries have their respective notices. Rebuild/install instructions and exact
SDK/source pins are provided; no release APK or store publication is claimed yet.
