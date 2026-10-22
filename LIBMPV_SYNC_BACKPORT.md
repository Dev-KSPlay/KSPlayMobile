# Android libmpv OpenGL sync-object backport

## Dependency and failure

Android previously used Maven Central's
`io.github.abdallahmehiz:mpv-android-lib:0.1.12` (published February 13, 2026).
Its AAR contains `libmpv.so` for `arm64-v8a`, `armeabi-v7a`, `x86`, and
`x86_64`, together with JNI, FFmpeg, XML, and C++ shared libraries.
Libplacebo is statically linked into each `libmpv.so`, rather than shipped as
a separate `libplacebo.so`. Its compiled version is `v7.360.0`; MPV's compiled
revision is `76a5eba991733f41310912c79c60f6c565a77cc9`.
The Android dependency is independent of iOS's MPVKit integration.

In libplacebo v7.360.0, `gl_poll_callbacks()` removes completed callbacks
without deleting their GL sync objects. Upstream commit
[`c93aa134ab62365ce1177efff99b8e1e66a818e7`](https://github.com/haasn/libplacebo/commit/c93aa134ab62365ce1177efff99b8e1e66a818e7)
(February 18, 2026) deletes the sync object before invoking its callback.
The upstream [Qualcomm GPU report](https://github.com/mpv-player/mpv/issues/12708),
[purple-screen report](https://github.com/mpv-android/mpv-android/issues/977),
and [descriptor-exhaustion report](https://github.com/mpv-player/mpv/issues/11721)
were closed with references to that fix.

[NuvioMobile #1730](https://github.com/NuvioMedia/NuvioMobile/issues/1730)
reports descriptor exhaustion, Adreno sync-object failures, and frame-upload
errors after extended GPU Next playback. Switching to GPU helped the reporter.
Those symptoms are consistent with this upstream leak; that device's exact
failure has not been reproduced here. Issue #1730 was closed for inactivity,
not verified fixed.

## Integration

`composeApp/libs/mpv-android-lib-0.1.12-sync-fix.aar` replaces the Maven
dependency through the project's existing local-AAR mechanism. The archive
retains every upstream entry and replaces **only** these entries:

```
jni/arm64-v8a/libmpv.so
jni/armeabi-v7a/libmpv.so
jni/x86/libmpv.so
jni/x86_64/libmpv.so
```

The original AAR's SHA-256 is
`bb1a007c545cc7ac3304293ae79866b5361a48449ee7648c4030d5355869effc`.
`scripts/package-mpv-sync.py` verifies that checksum, strips the rebuilt
libraries, and checks that all other entries remain byte-identical, including
`classes.jar`, consumer rules, JNI wrapper, FFmpeg, XML, and C++ libraries.
Both Android distributions use this AAR. No renderer, decoder, subtitle,
seeking, resume, or device-detection code changes are required.
GPU Next and GPU remain selectable.

There is no newer release under the original Maven coordinates. The maintained
`io.github.secozzi:mpv-android-lib:0.1.13` fork was also examined, but its AAR
omits FFmpeg and C++ shared libraries still required by its `libmpv.so`.
It therefore cannot replace the current dependency on its own. This backport
avoids that packaging change and unrelated native upgrades.

## Rebuilding

Use Linux with Python 3, a host C compiler, Meson, Ninja, pkg-config, autoconf,
automake, libtool, nasm, wget, Git, and Android NDK **29.0.14206865 (r29)**.
The script installs no host tools and refuses an existing build directory.

```bash
export ANDROID_NDK_HOME=/absolute/path/to/android-ndk-r29
bash scripts/rebuild-mpv-sync.sh /absolute/path/to/new-build-directory
```

Set `ORIGINAL_MPV_AAR` to an existing upstream `0.1.12` AAR to package without
downloading it again. Its checksum is verified in either case.

The script pins upstream Android build scripts to
`96c3507c5fc8eedbf5fff458101cad794392eca7` (`v0.1.12`), MPV to the revision
above, and libplacebo to
`b2ea27dceb6418aabfe9121174c6dbb232942998` (`v7.360.0`).
`scripts/libplacebo-sync.patch` is the one-line equivalent of `c93aa13`.
Git-based native dependencies are pinned in the script; remaining versioned
tarballs come from that pinned upstream `buildscripts/include/depinfo.sh`.
Libplacebo and its generated GL loader are built through upstream Meson/Ninja
scripts for all four ABIs, then statically linked into rebuilt MPV libraries.
Upstream JNI wrapper and all other AAR entries come from the checksum-verified
published artifact, not the rebuilt dependency outputs.

Rebuilds use pinned sources and `SOURCE_DATE_EPOCH`. Host-tool versions and
absolute build paths can still affect native bytes; bit-for-bit reproducibility
across different hosts is not claimed.

## Verification

Use Java 21–25 for host tests: pinned Robolectric 4.16 requires Java 21 for
Android SDK 36, while its ASM 9.8 cannot read Java 26 classes. This does not
require changing application dependencies.

`scripts/test-libplacebo-sync.py` compiles the actual `gl_poll_callbacks()`
function with fake GL entry points on the host. It checks successful waits,
destruction before callback invocation, timeout retention, failed waits, and
empty-queue polling without double destruction. Original v7.360.0 source fails;
patched source passes. The rebuild script requires both results.

```bash
python3 scripts/test-libplacebo-sync.py /path/to/patched/libplacebo/src/opengl/utils.c
./gradlew :androidApp:assembleFullDebug :composeApp:testAndroidHostTest -Pnuvio.android.distribution=full
./gradlew :androidApp:assemblePlaystoreDebug -Pnuvio.android.distribution=playstore
```

Long-duration Adreno validation remains necessary. On affected hardware, select
libmpv and GPU Next, play continuously for 30–45 minutes, sample `/proc/<pid>/fd`
and native memory, and check logcat for `GL_OUT_OF_MEMORY`, `Too many open files`,
`syncobj_create`, `GL_INVALID_OPERATION`, `GL_INVALID_VALUE`, and
`Failed uploading frame`. Repeat with GPU and hardware/software decoding.
Host regression tests or emulator playback do not establish resolution of
device-specific purple output or descriptor exhaustion.

## Included artifact audit (October 22, 2026)

AAR SHA-256:
`98ac952ad7b20b0ae74ace61716cc0ce65dca8474d37e2307b5391e0aaba6afb`.

| ABI | Rebuilt `libmpv.so` SHA-256 |
| --- | --- |
| arm64-v8a | `d3c00fcebaf9f7f67a3232d75f82c9f3e014b51d383822df14b1e2ff2794f2a4` |
| armeabi-v7a | `fbcead05f8f652cbcd430daaad7f812ebd89788c42af1b339751eae26fb4bbbc` |
| x86 | `a3c9fa595ba1c7c9adb9cc0055ef82a8a5a126aab812b64936234b563608f8d6` |
| x86_64 | `11f36fd42b5eae835cdba5e736a3cc175ebb161fb35ac2dde3280d08ce92d584` |

All four native builds completed. ELF architecture checks, exported-symbol
equality, shared-library dependency equality, and absence of new strong imports
passed against the original AAR. Every archive entry outside the four libraries
is byte-identical. The original ARM64 library also matches the compiled
unpatched callback function's 148-byte prefix and completed-callback sequence,
confirming that its version string does not conceal this backport.

An isolated Android 16 x86_64/SwiftShader probe loaded the exact final x86_64
library and rendered looping 640×360, 30 fps H.264 through the unchanged JNI
wrapper with software decoding. GPU Next ran for 21 minutes with 131 file
descriptors and no targeted GL/resource errors before Android's framework
restarted. That interruption leaves the 30–45-minute endurance check incomplete;
its cause was not established. A separate GPU smoke test completed 190 seconds
with advancing frames, descriptors settling from 135 to 134, and no targeted
errors. Screenshots showed the expected test pattern. The emulator dropped
frames under load, so these results do not establish smooth playback or Adreno
stability. No physical Adreno device was connected.

## Host-test baseline

With the original Maven AAR and Android Studio's Java 25 runtime,
`:composeApp:testAndroidHostTest -Pnuvio.android.distribution=full` ran
1,715 tests with three assertion failures and no errors. Failures were:

- `RatingsVisibilityTest.allEpisodeLayoutsRespectVisibilityForFetchedAndAddonRatings`
- `DownloadSubtitlesTest.externalPlayersReceiveReadableLocalSubtitlesWithoutAddonRequests`
- `WatchProgressIdentityTest.legacy payload derives episode progress key and preserves completed position`

These failures occurred before restoring the patched dependency. Java 17 and
26 were unsuitable test runtimes for the pinned Android SDK/Robolectric setup;
the Java 25 run provides the baseline for assessing regressions.

Full debug APK assembled successfully using
`:androidApp:assembleFullDebug -Pnuvio.android.distribution=full`. Its four
`libmpv.so` files match the patched AAR exactly. Comparison against a debug APK
built from the original Maven dependency found identical sets of 88 native
libraries, with changes confined to those four files. All other packaged
native libraries, including the C++ runtime selected from overlapping
dependencies, remain byte-identical to that baseline.

The patched Java 25 host-test run also executed 1,715 tests: the same three
failures, zero errors, and zero skips. Failure identities and messages match
baseline exactly; no new host-test failures were observed. The suite remains
failing overall, and these unrelated assertions were not changed.

The Full APK installed and launched successfully on the isolated Android 16
emulator. Its process remained alive on follow-up, then was stopped after the
launch smoke test. This does not constitute full-app media playback validation.

Playstore debug APK also assembled successfully using
`:androidApp:assemblePlaystoreDebug -Pnuvio.android.distribution=playstore`.
All four packaged MPV libraries match the patched AAR exactly; the archive
contains 84 native libraries and no separate `libplacebo.so`.

Native builds and regression checks ran on Linux/WSL; Android Gradle builds and
host tests ran on Windows using Android Studio Java 25. The Nuvio helper's
verification planning exited early in this isolated worktree, so verification
used the focused Gradle commands above. A temporary dependency-audit task also
hit Gradle artifact-variant ambiguity; direct APK byte audits provide the
packaging evidence reported here. Neither helper workaround changes repository
build logic. The standalone fresh-clone rebuild script was syntax-checked;
actual native builds used a task-owned copy of pinned sources and existing
same-revision dependency caches rather than rerunning that script from scratch.
