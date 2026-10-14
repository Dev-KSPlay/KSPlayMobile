package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class PlayerSurfaceGestureCallbacks(
    val onSurfaceTap: State<(Offset) -> Unit>,
    val onSurfaceDoubleTap: State<(Offset) -> Unit>,
    val seekTapEnabledAt: State<(Offset) -> Boolean>,
    val onSeekTap: State<(Offset) -> Unit>,
    val activateHoldToSpeed: State<() -> Unit>,
    val deactivateHoldToSpeed: State<() -> Unit>,
    val showHorizontalSeekPreview: State<(Long, Long) -> Unit>,
    val showBrightnessFeedback: State<(Float) -> Unit>,
    val showVolumeFeedback: State<(PlayerAudioLevel) -> Unit>,
    val clearLiveGestureFeedback: State<() -> Unit>,
    val revealLockedOverlay: State<() -> Unit>,
    val isHoldToSpeedGestureActive: State<Boolean>,
    val touchGesturesEnabled: State<Boolean>,
    val playerControlsLocked: State<Boolean>,
    val currentPositionMs: State<Long>,
    val currentDurationMs: State<Long>,
    val commitHorizontalSeek: State<(Long) -> Unit>,
)

internal fun PlayerScreenRuntime.showGestureFeedback(feedback: GestureFeedbackState) {
    gestureMessageJob?.cancel()
    gestureFeedback = feedback
    gestureMessageJob = scope.launch {
        delay(900)
        gestureFeedback = null
    }
}

internal fun PlayerScreenRuntime.showGestureMessage(message: String) {
    showGestureFeedback(GestureFeedbackState(message = message))
}

internal fun PlayerScreenRuntime.clearLiveGestureFeedback() {
    liveGestureFeedback = null
    horizontalSeekPreviewPositionMs = null
}

internal fun PlayerScreenRuntime.revealLockedOverlay() {
    controlsVisible = false
    lockedOverlayVisible = true
}

internal fun PlayerScreenRuntime.lockPlayerControls() {
    playerControlsLocked = true
    controlsVisible = false
    lockedOverlayVisible = false
    pausedOverlayVisible = false
    isScrubbingTimeline = false
    scrubbingPositionMs = null
    gestureMessageJob?.cancel()
    gestureFeedback = null
    liveGestureFeedback = null
    horizontalSeekPreviewPositionMs = null
    renderedGestureFeedback = null
    showAudioModal = false
    showSubtitleModal = false
    showVideoSettingsModal = false
    showStreamInfo = false
    showSourcesPanel = false
    showEpisodesPanel = false
    episodeStreamsPanelState = EpisodeStreamsPanelState()
    PlayerStreamsRepository.clearEpisodeStreams()
}

internal fun PlayerScreenRuntime.unlockPlayerControls() {
    playerControlsLocked = false
    lockedOverlayVisible = false
    controlsVisible = true
}

internal fun PlayerScreenRuntime.showSeekFeedback(direction: PlayerSeekDirection, amountMs: Long) {
    val seconds = amountMs / 1000L
    if (seconds <= 0L) return
    showGestureFeedback(
        GestureFeedbackState(
            messageRes = if (direction == PlayerSeekDirection.Forward) {
                Res.string.compose_player_seek_feedback_forward
            } else {
                Res.string.compose_player_seek_feedback_backward
            },
            messageArgs = listOf(seconds),
            icon = if (direction == PlayerSeekDirection.Forward) {
                GestureFeedbackIcon.SeekForward
            } else {
                GestureFeedbackIcon.SeekBackward
            },
        ),
    )
}

internal fun PlayerScreenRuntime.showHorizontalSeekPreview(previewPositionMs: Long, baselinePositionMs: Long) {
    val deltaMs = previewPositionMs - baselinePositionMs
    val direction = if (deltaMs < 0L) PlayerSeekDirection.Backward else PlayerSeekDirection.Forward
    horizontalSeekPreviewPositionMs = previewPositionMs
    liveGestureFeedback = GestureFeedbackState(
        message = formatPlaybackTime(previewPositionMs),
        icon = if (direction == PlayerSeekDirection.Forward) {
            GestureFeedbackIcon.SeekForward
        } else {
            GestureFeedbackIcon.SeekBackward
        },
        secondaryMessageRes = if (deltaMs >= 0L) {
            Res.string.compose_player_seek_delta_forward
        } else {
            Res.string.compose_player_seek_delta_backward
        },
        secondaryMessageArgs = listOf((abs(deltaMs) / 1000f).roundToInt()),
        secondaryMessageColor = if (direction == PlayerSeekDirection.Forward) {
            Color(0xFF6EE7A8)
        } else {
            Color(0xFFFF9A76)
        },
    )
}

internal fun PlayerScreenRuntime.showBrightnessFeedback(level: Float) {
    val percentage = (level.coerceIn(0f, 1f) * 100f).roundToInt()
    showGestureFeedback(
        GestureFeedbackState(
            messageRes = Res.string.compose_player_brightness_level,
            messageArgs = listOf("$percentage%"),
            icon = GestureFeedbackIcon.Brightness,
            level = level.coerceIn(0f, 1f),
        ),
    )
}

internal fun PlayerScreenRuntime.showVolumeFeedback(level: PlayerAudioLevel) {
    val percentage = (level.fraction.coerceIn(0f, 1f) * 100f).roundToInt()
    showGestureFeedback(
        GestureFeedbackState(
            messageRes = if (level.isMuted) {
                Res.string.compose_player_muted
            } else {
                Res.string.compose_player_volume_level
            },
            messageArgs = if (level.isMuted) emptyList() else listOf("$percentage%"),
            icon = if (level.isMuted) GestureFeedbackIcon.VolumeMuted else GestureFeedbackIcon.Volume,
            isDanger = level.isMuted,
            level = if (level.isMuted) 0f else level.fraction.coerceIn(0f, 1f),
        ),
    )
}

internal fun PlayerScreenRuntime.togglePlayback() {
    if (playbackSnapshot.isPlaying) {
        shouldPlay = false
        playerController?.pause()
    } else {
        if (playbackSnapshot.isEnded) {
            playerController?.seekTo(0L)
        }
        shouldPlay = true
        playerController?.play()
    }
    controlsVisible = true
}

internal fun PlayerScreenRuntime.seekBy(offsetMs: Long) {
    val fromMs = playbackSnapshot.positionMs
    val targetMs = (fromMs + offsetMs).coerceAtLeast(0L)
        .let { if (playbackSnapshot.durationMs > 0L) it.coerceAtMost(playbackSnapshot.durationMs) else it }
    lastManualSkipSeekPositions = fromMs to targetMs
    playerController?.seekBy(offsetMs)
    scheduleProgressSyncAfterSeek()
    controlsVisible = true
    when {
        offsetMs > 0L -> showSeekStepFeedback(PlayerSeekDirection.Forward, offsetMs, fromMs)
        offsetMs < 0L -> showSeekStepFeedback(PlayerSeekDirection.Backward, abs(offsetMs), fromMs)
    }
}

/**
 * Seek feedback for a single step. Legacy layout keeps the top-center text; the modern layout
 * drives the YouTube-style side indicator (`+ N` / `- N`) from [PlayerScreenRuntime.accumulatedSeekState].
 */
private fun PlayerScreenRuntime.showSeekStepFeedback(
    direction: PlayerSeekDirection,
    stepMs: Long,
    fromPositionMs: Long,
) {
    if (playerSettingsUiState.useLegacyPlayerLayout) {
        showSeekFeedback(direction, stepMs)
    } else {
        advanceAccumulatedSeek(direction, stepMs, fromPositionMs.coerceAtLeast(0L))
        scheduleAccumulatedSeekReset()
    }
}

private fun PlayerScreenRuntime.advanceAccumulatedSeek(
    direction: PlayerSeekDirection,
    stepMs: Long,
    baselinePositionMs: Long = playbackSnapshot.positionMs.coerceAtLeast(0L),
): PlayerAccumulatedSeekState {
    val currentSeekState = accumulatedSeekState
    val nextState = if (currentSeekState?.direction == direction) {
        currentSeekState.copy(amountMs = currentSeekState.amountMs + stepMs)
    } else {
        PlayerAccumulatedSeekState(
            direction = direction,
            baselinePositionMs = baselinePositionMs,
            amountMs = stepMs,
        )
    }
    accumulatedSeekState = nextState
    return nextState
}

private fun PlayerScreenRuntime.scheduleAccumulatedSeekReset() {
    val resetDelayMs = if (playerSettingsUiState.useLegacyPlayerLayout) {
        PlayerDoubleTapSeekResetDelayMs
    } else {
        PlayerSeekIndicatorHoldMs
    }
    accumulatedSeekResetJob?.cancel()
    accumulatedSeekResetJob = scope.launch {
        delay(resetDelayMs)
        accumulatedSeekState = null
    }
}

/**
 * TV-style bottom seek bar (modern layout): where the pending seek lands, shown only while the main
 * controls are hidden. Follows a horizontal swipe preview, or the double-tap / tap / button seek
 * that is currently on screen. Null when nothing should be shown.
 */
internal fun PlayerScreenRuntime.currentSeekProgress(): PlayerSeekProgress? {
    if (playerControlsLocked || controlsVisible || pausedOverlayVisible || errorMessage != null) return null
    val durationMs = playbackSnapshot.durationMs.takeIf { it > 0L } ?: return null
    val positionMs = horizontalSeekPreviewPositionMs
        ?: accumulatedSeekState?.targetPositionMs(durationMs)
        ?: return null
    return PlayerSeekProgress(
        positionMs = positionMs.coerceIn(0L, durationMs),
        durationMs = durationMs,
    )
}

/**
 * While the seek indicator is visible (modern layout), a single tap on the left/right zone keeps
 * seeking in that direction, like YouTube. Returns null when taps should behave normally.
 */
internal fun PlayerScreenRuntime.seekTapDirection(offset: Offset): PlayerSeekDirection? {
    if (playerSettingsUiState.useLegacyPlayerLayout) return null
    if (playerControlsLocked || !playerSettingsUiState.touchGesturesEnabled) return null
    if (accumulatedSeekState == null) return null
    return when {
        offset.x < layoutSize.width * PlayerLeftGestureBoundary -> PlayerSeekDirection.Backward
        offset.x > layoutSize.width * PlayerRightGestureBoundary -> PlayerSeekDirection.Forward
        else -> null
    }
}

internal fun PlayerScreenRuntime.handleDoubleTapSeek(direction: PlayerSeekDirection) {
    val currentPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
    val nextState = advanceAccumulatedSeek(direction, PlayerDoubleTapSeekStepMs)

    val targetPositionMs = nextState.targetPositionMs(playbackSnapshot.durationMs)
    lastManualSkipSeekPositions = currentPositionMs to targetPositionMs
    playerController?.seekTo(targetPositionMs)
    scheduleProgressSyncAfterSeek()
    if (playerSettingsUiState.useLegacyPlayerLayout) {
        showSeekFeedback(direction, nextState.amountMs)
    }
    scheduleAccumulatedSeekReset()
}

internal fun PlayerScreenRuntime.cycleResizeMode() {
    val nextMode = resizeMode.next()
    resizeMode = nextMode
    lastSyncedSettingsResizeMode = nextMode
    PlayerSettingsRepository.setResizeMode(nextMode)
    showGestureMessage(
        when (nextMode) {
            PlayerResizeMode.Fit -> resizeModeFitLabel
            PlayerResizeMode.Fill -> resizeModeFillLabel
            PlayerResizeMode.Zoom -> resizeModeZoomLabel
        },
    )
    controlsVisible = true
}

internal fun PlayerScreenRuntime.cyclePlaybackSpeed() {
    val speeds = listOf(1f, 1.25f, 1.5f, 2f)
    val current = playbackSnapshot.playbackSpeed
    val next = speeds.firstOrNull { it > current + 0.01f } ?: speeds.first()
    playerController?.setPlaybackSpeed(next)
    showGestureMessage(formatPlaybackSpeedLabel(next))
    controlsVisible = true
}

internal fun PlayerScreenRuntime.activateHoldToSpeed() {
    if (!playerSettingsUiState.holdToSpeedEnabled) return
    val controller = playerController ?: return
    if (speedBoostRestoreSpeed != null) return

    val targetSpeed = playerSettingsUiState.holdToSpeedValue
    val currentSpeed = playbackSnapshot.playbackSpeed
    if (abs(currentSpeed - targetSpeed) < 0.01f) return

    isHoldToSpeedGestureActive = true
    speedBoostRestoreSpeed = currentSpeed
    controller.setPlaybackSpeed(targetSpeed)
    liveGestureFeedback = GestureFeedbackState(
        message = formatPlaybackSpeedLabel(targetSpeed),
        icon = GestureFeedbackIcon.Speed,
    )
    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
}

internal fun PlayerScreenRuntime.deactivateHoldToSpeed() {
    isHoldToSpeedGestureActive = false
    val restoreSpeed = speedBoostRestoreSpeed ?: return
    playerController?.setPlaybackSpeed(restoreSpeed)
    speedBoostRestoreSpeed = null
    liveGestureFeedback = null
}

@Composable
internal fun PlayerScreenRuntime.rememberSurfaceGestureCallbacks(): PlayerSurfaceGestureCallbacks {
    val onSurfaceTap = rememberUpdatedState { offset: Offset ->
        if (playerControlsLocked) {
            revealLockedOverlay()
            return@rememberUpdatedState
        }
        val centerStart = layoutSize.width * PlayerLeftGestureBoundary
        val centerEnd = layoutSize.width * PlayerRightGestureBoundary
        if (controlsVisible && offset.x in centerStart..centerEnd) {
            controlsVisible = false
        } else {
            controlsVisible = !controlsVisible
        }
    }
    val onSurfaceDoubleTap = rememberUpdatedState { offset: Offset ->
        if (playerControlsLocked) {
            revealLockedOverlay()
            return@rememberUpdatedState
        }
        if (!playerSettingsUiState.touchGesturesEnabled) {
            controlsVisible = !controlsVisible
            return@rememberUpdatedState
        }
        when {
            offset.x < layoutSize.width * PlayerLeftGestureBoundary -> {
                handleDoubleTapSeek(PlayerSeekDirection.Backward)
            }
            offset.x > layoutSize.width * PlayerRightGestureBoundary -> {
                handleDoubleTapSeek(PlayerSeekDirection.Forward)
            }
            else -> controlsVisible = !controlsVisible
        }
    }
    val seekTapEnabledAt = rememberUpdatedState { offset: Offset -> seekTapDirection(offset) != null }
    val onSeekTap = rememberUpdatedState { offset: Offset ->
        val direction = seekTapDirection(offset)
        if (direction != null) handleDoubleTapSeek(direction)
    }
    return PlayerSurfaceGestureCallbacks(
        onSurfaceTap = onSurfaceTap,
        onSurfaceDoubleTap = onSurfaceDoubleTap,
        seekTapEnabledAt = seekTapEnabledAt,
        onSeekTap = onSeekTap,
        activateHoldToSpeed = rememberUpdatedState(::activateHoldToSpeed),
        deactivateHoldToSpeed = rememberUpdatedState(::deactivateHoldToSpeed),
        showHorizontalSeekPreview = rememberUpdatedState(::showHorizontalSeekPreview),
        showBrightnessFeedback = rememberUpdatedState(::showBrightnessFeedback),
        showVolumeFeedback = rememberUpdatedState(::showVolumeFeedback),
        clearLiveGestureFeedback = rememberUpdatedState(::clearLiveGestureFeedback),
        revealLockedOverlay = rememberUpdatedState(::revealLockedOverlay),
        isHoldToSpeedGestureActive = rememberUpdatedState(isHoldToSpeedGestureActive),
        touchGesturesEnabled = rememberUpdatedState(playerSettingsUiState.touchGesturesEnabled),
        playerControlsLocked = rememberUpdatedState(playerControlsLocked),
        currentPositionMs = rememberUpdatedState(playbackSnapshot.positionMs.coerceAtLeast(0L)),
        currentDurationMs = rememberUpdatedState(playbackSnapshot.durationMs),
        commitHorizontalSeek = rememberUpdatedState { targetPositionMs: Long ->
            lastManualSkipSeekPositions = playbackSnapshot.positionMs to targetPositionMs
            playerController?.seekTo(targetPositionMs)
            scheduleProgressSyncAfterSeek()
        },
    )
}
