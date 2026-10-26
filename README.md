# scrctl-android

A focused native Kotlin / Material 3 Android frontend for iPhone Wi-Fi mirroring
and control, and an application-sandbox validation host for XDeviceBridge.

The SDK is pinned to **XDeviceBridge v1.0.1**, commit
`6248da9f5a6ccdca345f45858f4a0c3fe8ef5a76`.
The App embeds libxdb_server and libxdb_client through JNI. It never executes an
external xdb program. Pairing records belong to xdb in App-private persistent
storage; the App does not parse credentials. DDI uses App cache storage.

Implementation is in progress. Build, simulated tests and real-device acceptance
will be recorded separately in docs/VALIDATION.md.

## Initial scope

- Wi-Fi discovery, pairing PIN/progress/cancellation and DDI preparation.
- Software-decoded video with aspect ratio, rotation and mapped single touch.
- Home, volume and side-button controls.
- Foreground-only sessions; network changes and backgrounding cancel native work.

v1.0.1 has no public branding configuration. Pairing retains its existing
`xdb - <Android model>` display name and Apple DNS-SD service types.
NsdManager discovery/advertisement is deferred; the initial App uses the SDK's
existing mDNS implementation.
