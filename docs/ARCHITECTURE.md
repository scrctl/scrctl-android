# Android integration

XDB is pinned to v1.0.1 / 6248da9f5a6ccdca345f45858f4a0c3fe8ef5a76.
The build script rejects another commit or tracked SDK modifications. The App
never runs an external xdb executable, changes XDB_PORT, reads pairing records,
or substitutes Android system discovery for the pinned SDK implementation.

- JNI starts Server with port 0 and filesDir/xdb, then connects each client using
  xdb_server_port. Independent embedded Apps use independent ports and credentials.
- Management operations run on a serial worker. Long operations expose progress,
  transient PIN and cancellation. DDI uses cacheDir/ddi; ordinary preparation does
  not request replacement. Completed device changes cannot be rolled back on cancel.
- Mirroring owns a device session. Separate attached clients receive frames and
  send input. Software HEVC decoding occurs in Server. JNI validates BGRA geometry,
  respects row pitch and converts channels directly into a Bitmap.
- The View holds one displayed and one pending frame. It drops superseded pending
  frames rather than accumulating UI callbacks. Display and input use the same
  geometry snapshot, visible-panel crop and inverse rotation. Unknown geometry
  disables coordinate input. Multitouch releases the single supported contact.
- Input move events coalesce; discrete down/up ordering is retained. Input failure
  closes the session so best-effort server cleanup releases held state.
- Backgrounding/exit invalidates the generation, interrupts clients immediately,
  then drains workers and tears down Server off the UI thread. No foreground
  service or background decoding mode is implemented.
- Default-network/address changes are debounced. Loss stops the session; a new
  network or return to foreground creates a new Server/client generation and
  attempts the selected mirror once. Failures remain visible; no tight retry loop.
- MulticastLock is held only while the Activity is started. Target SDK 36 uses
  INTERNET for LAN sockets; ACCESS_LOCAL_NETWORK is intentionally not requested
  as if target 37. Raising the target requires a separate permission migration.

The UI has a device list and an immersive mirror screen. Back disconnects and
returns to the list. Home/volume/side-button actions live in the mirror menu.

## Deferred SDK changes

v1.0.1 exposes no host-branding option. Preserve `xdb - <Android model>` and Apple's
DNS-SD types. Do not patch SDK strings to present a different brand.

A later NsdManager backend should subscribe continuously to only
`_remotepairing._tcp.local.` and advertise
`_remotepairing-pairable-host._tcp.local.` during pairing. Server, not the UI,
should maintain the cache and match credentials. Validate IPv6 scope, multiple
interfaces, TXT/address changes, removals, network/lifecycle transitions and exact
pairing advertisement compatibility; preserve a fallback where necessary.

## Distribution

Initial ABI: arm64-v8a; Android 8/API 26 or later. No audio, recording, text input,
USB Host adapter or hardware-decoder dependency is introduced in this first scope.
One NDK shared C++ runtime is packaged for every native DSO. Debug APKs include
native notices and version/hash metadata in assets. Matching-source CI artifacts
include the exact App, SDK and pinned OpenSSL/curl/FFmpeg archives and build cache.
The NDK toolchain notice covers the C++ runtime. SDK-managed dependencies are
included in its source and fetched from the pinned recipes when rebuilding.

## Android HTTPS trust integration

The SDK checkout remains unmodified v1.0.1. Before starting Server, the JNI
wrapper exports Android's default X509TrustManager accepted roots into an
App-private PEM bundle. An App-owned link wrapper around the static libcurl's
curl_easy_init configures new handles with this CA file explicitly. It does not
change verification flags; certificate-chain and hostname checks remain enabled.
The SDK's TSS code subsequently overrides those defaults with its dedicated Apple
root blob, preserving its signing policy.

Explicit configuration avoids Android's old certificate filename hashes, the
Conscrypt APEX directory migration and reliance on environment trust paths
that OpenSSL ignores when a process has AT_SECURE set. The link adaptation is limited to the
embedded xdb_server library, does not alter the SDK checkout or public ABI, and
is part of the App source/build recipe. Native curl handles are not exposed to
other App components.
