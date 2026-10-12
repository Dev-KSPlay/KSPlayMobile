# Nuvio – Manual "Vertical Video" (portrait lock) button

Base: `NuvioMedia/NuvioMobile`, branch `cmp-rewrite`, commit `ee3d915` ("chore(store): publish 0.5.9-beta").
The local `iosApp/iosApp/OrientationLockCoordinator.swift` you uploaded was byte-identical to that commit.

## What the feature does
- On **iOS and Android**, a new **Vertical Video** button (Material `StayCurrentPortrait` icon) appears in the player's bottom action row, right after the aspect-ratio/resize button.
- Tapping it turns on portrait lock. The device rotates to portrait and stays there, and the icon turns the theme's primary color to show it's on.
- Tapping it again turns portrait lock off and switches back to the player's normal landscape lock.
- Closing the player while it's on unlocks orientation as usual. The setting isn't saved, so every new player session starts in landscape.

## How it works
The app already locks to landscape **declaratively**: `MainAppContent.kt` calls the composable `LockPlayerToLandscape()` while the player route is visible. It is a `DisposableEffect` that posts `NuvioPlayerLockLandscape` and, when disposed, `NuvioPlayerUnlockOrientation`.
The portrait lock uses the same pattern:

- `RenderPlayerRuntimeUi()` calls the new composable `LockPlayerToPortrait()` while `runtime.manualVerticalVideoMode == true`.
- The iOS actual keeps separate counters for landscape and portrait locks:
  - **Portrait wins:** when portrait is on, a landscape lock request doesn't post anything.
  - **Turning portrait off:** this posts `NuvioPlayerLockLandscape` again if the player still holds a landscape lock. Otherwise it posts `NuvioPlayerUnlockOrientation`.
- Because of this, no separate `UnlockPlayerOrientation()` expect function was needed. A non-composable `toggleVerticalVideoMode()` → `LockPlayerToPortrait()` call, as first planned, could not work: the existing lock functions are `@Composable` and are scoped with effects.

## Files changed

| # | File | Change |
|---|------|--------|
| 1 | `iosApp/iosApp/OrientationLockCoordinator.swift` | Added a file-private `lockPlayerToPortraitNotification = Notification.Name("NuvioPlayerLockPortrait")`, following the existing file-private style instead of a `static let`. Added a private `PlayerOrientationLock` enum (`none` / `landscape` / `portrait` → `.allButUpsideDown` / `.landscape` / `.portrait`). Added an observer for the portrait notification. `setLandscapeLock(enabled:)` is replaced by `applyLock(_:)`. `requestOrientationUpdate` now takes the lock kind. On iOS < 16, portrait forces `UIInterfaceOrientation.portrait`; landscape and unlock behave as before. |
| 2 | `composeApp/src/commonMain/.../player/PlayerPlatformEffects.kt` | Added `@Composable expect fun LockPlayerToPortrait()`. |
| 3 | `composeApp/src/iosMain/.../player/PlayerPlatformEffects.ios.kt` | Added the `NuvioPlayerLockPortrait` constant, `playerPortraitLockCount` and a small `postPlayerOrientationNotification` helper. `LockPlayerToLandscape()` now does nothing while portrait is active. Added `actual fun LockPlayerToPortrait()` (a `DisposableEffect` with a counter) that switches back to landscape or unlocks when it's disposed. |
| 4 | `composeApp/src/androidMain/.../player/PlayerPlatformEffects.android.kt` | Added a private `PlayerOrientationLocks` object that counts landscape and portrait locks (the same idea as the iOS counters). It records the activity's `requestedOrientation` when the first lock is taken. Portrait (`SCREEN_ORIENTATION_SENSOR_PORTRAIT`) wins over landscape (`SCREEN_ORIENTATION_SENSOR_LANDSCAPE`). When no locks are left, it restores the recorded orientation, or `SCREEN_ORIENTATION_UNSPECIFIED` if none was recorded. `LockPlayerToLandscape()` keeps its existing phone-only / no-multi-window check and now goes through this object; the new `actual fun LockPlayerToPortrait()` does too. With one shared state, the screen ends up correct whichever lock is released first. With separate save/restore in each lock, closing the player could leave it stuck in landscape. |
| 5 | `composeApp/src/commonMain/.../player/PlayerScreenRuntimeState.kt` | Added `var manualVerticalVideoMode by mutableStateOf(false)` next to `resizeMode`. |
| 6 | `composeApp/src/commonMain/.../player/PlayerScreenRuntimeGestureActions.kt` | Added `PlayerScreenRuntime.toggleVerticalVideoMode()` next to `cycleResizeMode()`. It flips the flag and keeps the controls visible, like the other control actions. |
| 7 | `composeApp/src/commonMain/.../player/PlayerScreenRuntimeUi.kt` | Calls `LockPlayerToPortrait()` while `manualVerticalVideoMode && !isInPip`. Passes `isVerticalVideoMode` and `onVerticalVideoClick` to `PlayerControlsShell`. The click handler is always set (`{ toggleVerticalVideoMode() }`), so the button shows on both platforms. |
| 8 | `composeApp/src/commonMain/.../player/PlayerControls.kt` | `PlayerControlsShell` takes two new parameters with defaults: `isVerticalVideoMode: Boolean = false` and `onVerticalVideoClick: (() -> Unit)? = null`. It passes them to `PlayerControlActions`. |
| 9 | `composeApp/src/commonMain/.../player/PlayerControlActions.kt` | Added the matching parameters and a new `PlayerControlAction` (`Icons.Rounded.StayCurrentPortrait`, label `player_vertical_video`) after the resize button. Added `isActive` to `PlayerControlAction` and `PlayerAction`; active buttons use `MaterialTheme.colorScheme.primary` instead of white. |
| 10 | `composeApp/src/commonMain/composeResources/values/strings.xml` | Added `<string name="player_vertical_video">Vertical Video</string>` in the "PlayerControls a11y" block. Translated `values-*` files were left alone, so they fall back to English. |

No Xcode project or Info.plist change is needed. `INFOPLIST_KEY_UISupportedInterfaceOrientations_iPhone` already includes `UIInterfaceOrientationPortrait`.

## How to apply
**Option A – patch (recommended):** from the repo root on `cmp-rewrite`:
```bash
git apply --check vertical_video.patch && git apply vertical_video.patch
```
The patch is `Nuvio_modified/vertical_video.patch` inside the zip.

**Option B – copy files:** unzip `Nuvio_vertical_video_changes.zip` and copy the `composeApp/` and `iosApp/` folders over your project root. The paths match the repo. Only do this if your local files still match `cmp-rewrite@ee3d915`; otherwise use the patch.

Then rebuild the shared framework and run the iOS app from Xcode.

## Options
- **Android:** turned on (see #4 and #7). Android phones and tablets both show the button. On tablets and in multi-window mode the player isn't forced into landscape, so turning the button off there simply unlocks orientation.
- **Legacy player layout:** the button is only in the modern bottom action row (`PlayerControlActions`). The legacy layout (`useLegacyPlayerLayout`, `PlayerHeader` / `ProgressControls`) doesn't have it.

## Verification status
- **Not compiled.** This environment has no Android SDK or Xcode/Kotlin-Native toolchain, so the Kotlin and Swift changes have not been built or run.
- **Checked by hand:**
  - `Icons.Rounded.StayCurrentPortrait` exists in `material-icons-extended`; the project already depends on `compose.materialIconsExtended`.
  - Every `expect` has an `actual` in `androidMain` and `iosMain`. These are the only source sets with `LockPlayerToLandscape` actuals.
  - The two new `PlayerControlsShell` parameters have defaults. `PlayerControlActions` is only called from `PlayerControls.kt`, which was updated.
  - The combined patch (iOS + Android) applies cleanly to `ee3d915`.
- **Please check on a device:** turn the button on and off during playback, close the player while it's on, and test on iOS 15 (the `UIDevice` fallback path), iOS 16+, an Android phone and an Android tablet or multi-window setup. All activities in the Android manifest declare `configChanges=...|orientation`, so rotating should not recreate the activity.
