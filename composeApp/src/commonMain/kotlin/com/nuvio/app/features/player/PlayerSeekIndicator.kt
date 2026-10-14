package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.accentBrush
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.core.ui.themePalette

private const val SeekChevronPulseMs = 360
private val SeekChevronEasing = CubicBezierEasing(0.3f, 0f, 0.2f, 1f)

/** Keeps the last shown value around so the indicator can fade out with its final number. */
private class LastSeekIndicatorValue {
    var value: PlayerAccumulatedSeekState? = null
}

/**
 * YouTube-style seek indicator for the modern player layout: `+ N ›` on the right for forward
 * seeks and `‹ - N` on the left for backward seeks, both vertically centered. [state] is the
 * accumulated double-tap / tap seek; it is shown while non-null.
 */
@Composable
internal fun PlayerSeekIndicator(
    state: PlayerAccumulatedSeekState?,
    horizontalSafePadding: Dp,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(modifier.fillMaxSize()) {
            PlayerSeekIndicatorSide(
                direction = PlayerSeekDirection.Backward,
                state = state,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = horizontalSafePadding + horizontalPadding),
            )
            PlayerSeekIndicatorSide(
                direction = PlayerSeekDirection.Forward,
                state = state,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = horizontalSafePadding + horizontalPadding),
            )
        }
    }
}

@Composable
private fun PlayerSeekIndicatorSide(
    direction: PlayerSeekDirection,
    state: PlayerAccumulatedSeekState?,
    modifier: Modifier,
) {
    val active = state?.takeIf { it.direction == direction }
    val last = remember { LastSeekIndicatorValue() }
    if (active != null) last.value = active
    val shown = active ?: last.value

    // Switching direction cross-fades: the old side fades out while the new side fades in.
    AnimatedVisibility(
        visible = active != null,
        modifier = modifier,
        enter = fadeIn(tween(130)),
        exit = fadeOut(tween(220)),
    ) {
        if (shown != null) {
            PlayerSeekIndicatorContent(direction = direction, state = shown)
        }
    }
}

@Composable
private fun PlayerSeekIndicatorContent(
    direction: PlayerSeekDirection,
    state: PlayerAccumulatedSeekState,
) {
    val seconds = (state.amountMs / 1000L).toInt()
    val label = if (direction == PlayerSeekDirection.Forward) "+ $seconds" else "\u2212 $seconds"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (direction == PlayerSeekDirection.Backward) {
            SeekChevrons(direction = direction, pulseKey = state)
            SeekIndicatorLabel(label)
        } else {
            SeekIndicatorLabel(label)
            SeekChevrons(direction = direction, pulseKey = state)
        }
    }
}

@Composable
private fun SeekIndicatorLabel(label: String) {
    Text(
        text = label,
        color = Color.White,
        style = MaterialTheme.nuvioTypeScale.bodyLg.copy(
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            fontFeatureSettings = "tnum",
            shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 8f),
        ),
        maxLines = 1,
        softWrap = false,
    )
}

/**
 * Chevron that pulses on every seek step: a second chevron unfolds from a thin line behind it and
 * the pair merges back into one (`›` -> `»` -> `›`). [pulseKey] changes on every step.
 */
@Composable
private fun SeekChevrons(
    direction: PlayerSeekDirection,
    pulseKey: Any,
) {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(pulseKey) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(SeekChevronPulseMs, easing = SeekChevronEasing))
    }
    Canvas(
        modifier = Modifier
            .size(width = 12.dp, height = 22.dp)
            .graphicsLayer { scaleX = if (direction == PlayerSeekDirection.Backward) -1f else 1f },
    ) {
        val t = progress.value
        drawSeekChevron(
            translateXDp = keyframeValue(t, 0f to -12f, 0.45f to -5f, 1f to 2f),
            scaleX = keyframeValue(t, 0f to 0.08f, 0.45f to 1f, 1f to 1f),
            alpha = keyframeValue(t, 0f to 0.9f, 0.45f to 1f, 1f to 0f),
        )
        drawSeekChevron(
            translateXDp = keyframeValue(t, 0f to -4f, 0.35f to 5f, 1f to 0f),
            scaleX = 1f,
            alpha = keyframeValue(t, 0f to 0.55f, 0.35f to 1f, 1f to 1f),
        )
    }
}

private fun DrawScope.drawSeekChevron(translateXDp: Float, scaleX: Float, alpha: Float) {
    if (alpha <= 0f) return
    val path = Path().apply {
        moveTo(2.dp.toPx(), 2.dp.toPx())
        lineTo(10.dp.toPx(), 11.dp.toPx())
        lineTo(2.dp.toPx(), 20.dp.toPx())
    }
    val pivot = Offset(size.width / 2f, size.height / 2f)
    val translatePx = translateXDp.dp.toPx()
    withTransform({
        translate(left = translatePx)
        scale(scaleX = scaleX, scaleY = 1f, pivot = pivot)
    }) {
        // Soft dark outline keeps the chevron readable on bright frames.
        drawPath(
            path = path,
            color = Color.Black.copy(alpha = 0.35f * alpha),
            style = Stroke(width = 3.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawPath(
            path = path,
            color = Color.White.copy(alpha = alpha),
            style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** Piecewise-linear interpolation between (progress, value) keyframes. */
private fun keyframeValue(t: Float, vararg frames: Pair<Float, Float>): Float {
    for (i in 1 until frames.size) {
        val (t1, v1) = frames[i]
        if (t <= t1) {
            val (t0, v0) = frames[i - 1]
            val fraction = if (t1 == t0) 1f else ((t - t0) / (t1 - t0)).coerceIn(0f, 1f)
            return v0 + (v1 - v0) * fraction
        }
    }
    return frames.last().second
}

/** Keeps the last shown value around so the bar can fade out with its final position. */
private class LastSeekProgressValue {
    var value: PlayerSeekProgress? = null
}

/**
 * TV-style seek bar for the modern player layout: a progress bar at the bottom with
 * `current / total` below it. [progress] is shown while non-null (the caller hides it while the
 * main controls are up).
 */
@Composable
internal fun PlayerSeekProgressOverlay(
    progress: PlayerSeekProgress?,
    horizontalSafePadding: Dp,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val last = remember { LastSeekProgressValue() }
    if (progress != null) last.value = progress
    val shown = progress ?: last.value

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = progress != null,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150)),
            ) {
                if (shown != null) {
                    PlayerSeekProgressContent(
                        progress = shown,
                        horizontalPadding = horizontalSafePadding + horizontalPadding,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerSeekProgressContent(
    progress: PlayerSeekProgress,
    horizontalPadding: Dp,
) {
    val fraction = if (progress.durationMs > 0L) {
        (progress.positionMs.toFloat() / progress.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(100),
        label = "seekProgressFraction",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Soft scrim so the bar and time stay readable over bright frames.
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
            .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Bottom))
            .padding(start = horizontalPadding, end = horizontalPadding, top = 28.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.3f))
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedFraction)
                    .background(MaterialTheme.themePalette.accentBrush()),
            )
        }
        Text(
            text = "${formatPlaybackTime(progress.positionMs)} / ${formatPlaybackTime(progress.durationMs)}",
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.nuvioTypeScale.bodyMd.copy(
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = "tnum",
                shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 8f),
            ),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.align(Alignment.End),
        )
    }
}
