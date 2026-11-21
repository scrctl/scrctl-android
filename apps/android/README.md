# scrctl GUI for Android

VNC and Mac Screen Sharing client. This Gradle project uses the Rust core
pinned at `vendor/scrctl` and has app ID `org.scrctl.android`. The existing
iPhone mirroring project remains at the repository root. Both builds share an
app ID, so only one can be installed at a time. Verify signing and version code
before replacing an installed build.

From the repository root, with Rust, cargo-ndk and Android NDK installed:

```sh
git submodule update --init vendor/scrctl
./scripts/build-android-core.sh
cd apps/android
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

The GUI source snapshot was imported from the OpenScreens project. See
[LICENSE-AGPL](../../LICENSE-AGPL) for its license.
