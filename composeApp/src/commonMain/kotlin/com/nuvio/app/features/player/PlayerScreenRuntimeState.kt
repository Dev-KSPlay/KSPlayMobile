package com.nuvio.app.features.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.addons.AddonsUiState
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsUiState
import com.nuvio.app.features.details.MetaScreenSettingsUiState
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.p2p.P2pSettingsUiState
import com.nuvio.app.features.p2p.P2pStreamingState
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.watched.WatchedUiState
import com.nuvio.app.features.watchprogress.WatchProgressUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

internal class PlayerScreenRuntime(
    args: PlayerScreenArgs,
) {
    var args by mutableStateOf(args)

    val title: String get() = args.title
    val profileId: Int get() = args.profileId
    val sourceUrl: String get() = args.sourceUrl
    val sourceAudioUrl: String? get() = args.sourceAudioUrl
    val sourceHeaders: Map<String, String> get() = args.sourceHeaders
    val sourceResponseHeaders: Map<String, String> get() = args.sourceResponseHeaders
    val streamType: String? get() = args.streamType
    val providerName: String get() = args.providerName
    val streamTitle: String get() = args.streamTitle
    val streamSubtitle: String? get() = args.streamSubtitle
    val initialBingeGroup: String? get() = args.initialBingeGroup
    val pauseDescription: String? get() = args.pauseDescription
    val logo: String? get() = args.logo
    val poster: String? get() = args.poster
    val background: String? get() = args.background
    val seasonNumber: Int? get() = args.seasonNumber
    val episodeNumber: Int? get() = args.episodeNumber
    val episodeTitle: String? get() = args.episodeTitle
    val episodeThumbnail: String? get() = args.episodeThumbnail
    val contentType: String? get() = args.contentType
    val videoId: String? get() = args.videoId
    val parentMetaId: String get() = args.parentMetaId
    val parentMetaType: String get() = args.parentMetaType
    val providerAddonId: String? get() = args.providerAddonId
    val torrentInfoHash: String? get() = args.torrentInfoHash
    val torrentFileIdx: Int? get() = args.torrentFileIdx
    val torrentFilename: String? get() = args.torrentFilename
    val torrentTrackers: List<String> get() = args.torrentTrackers
    val initialPositionMs: Long get() = args.initialPositionMs
    val initialProgressFraction: Float? get() = args.initialProgressFraction
    var externalSubtitles by mutableStateOf(args.externalSubtitles)
    val isSeries: Boolean get() = parentMetaType == "series"

    lateinit var scope: CoroutineScope
    lateinit var hapticFeedback: HapticFeedback

    var playerSettingsUiState: PlayerSettingsUiState = PlayerSettingsUiState()
    var p2pSettingsUiState by mutableStateOf(P2pSettingsUiState())
    var p2pStreamingState by mutableStateOf<P2pStreamingState>(P2pStreamingState.Idle)
    var metaScreenSettingsUiState: MetaScreenSettingsUiState = MetaScreenSettingsUiState()
    var watchedUiState: WatchedUiState = WatchedUiState()
    var watchProgressUiState: WatchProgressUiState = WatchProgressUiState()
    var sourceStreamsState by mutableStateOf(StreamsUiState())
    var episodeStreamsRepoState by mutableStateOf(StreamsUiState())
    var metaUiState: MetaDetailsUiState = MetaDetailsUiState()
    var addonsUiState: AddonsUiState = AddonsUiState()
    var addonSubtitles: List<AddonSubtitle> = emptyList()
    var isLoadingAddonSubtitles: Boolean = false

    var horizontalSafePadding: Dp = 0.dp
    var metrics: PlayerLayoutMetrics = PlayerLayoutMetrics.fromWidth(0.dp)
    var sliderEdgePadding: Dp = 0.dp
    var overlayBottomPadding: Dp = 0.dp
    var sideGestureSystemEdgeExclusionPx: Float = 0f
    var resizeModeFitLabel: String = ""
    var resizeModeFillLabel: String = ""
    var resizeModeZoomLabel: String = ""
    var downloadedLabel: String = ""
    var airsPrefix: String = ""
    var tbaLabel: String = ""
    var genericUnknownLabel: String = ""
    var parentalGuideLabels: ParentalGuideLabels = ParentalGuideLabels("", "", "", "", "", "", "", "")

    var gestureController: PlayerGestureController? = null

    var controlsActivityTick by mutableStateOf(0)
    var controlsVisible by mutableStateOf(false)
    var showRemainingTime by mutableStateOf(false)
    var playerControlsLocked by mutableStateOf(false)
    var activeSourceUrl by mutableStateOf(sourceUrl)
    var activeSourceAudioUrl by mutableStateOf(sourceAudioUrl)
    var activeSourceHeaders by mutableStateOf(sanitizePlaybackHeaders(sourceHeaders))
    var activeSourceResponseHeaders by mutableStateOf(sanitizePlaybackResponseHeaders(sourceResponseHeaders))
    var activeStreamType by mutableStateOf(streamType)
    var activeTorrentInfoHash by mutableStateOf(torrentInfoHash)
    var activeTorrentFileIdx by mutableStateOf(torrentFileIdx)
    var activeTorrentFilename by mutableStateOf(torrentFilename)
    var activeTorrentTrackers by mutableStateOf(torrentTrackers)
    var p2pResolvedSourceUrl by mutableStateOf<String?>(null)
    var activeSourceIdentityKey by mutableStateOf(
        torrentInfoHash?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { hash ->
            "torrent:$hash:${torrentFileIdx ?: -1}"
        } ?: sourceUrl.trim().takeIf { it.isNotBlank() }?.let { url -> "url:$url" },
    )
    var activeStreamTitle by mutableStateOf(streamTitle)
    var activeStreamSubtitle by mutableStateOf(streamSubtitle)
    var activeProviderName by mutableStateOf(providerName)
    var activeProviderAddonId by mutableStateOf(providerAddonId)
    var currentStreamBingeGroup by mutableStateOf(initialBingeGroup)
    var activeSeasonNumber by mutableStateOf(seasonNumber)
    var activeEpisodeNumber by mutableStateOf(episodeNumber)
    var activeEpisodeTitle by mutableStateOf(episodeTitle)
    var activeEpisodeThumbnail by mutableStateOf(episodeThumbnail)
    var activePauseDescription by mutableStateOf(pauseDescription)
    var activeVideoId by mutableStateOf(videoId)
    var activeInitialPositionMs by mutableStateOf(initialPositionMs)
    var activeInitialProgressFraction by mutableStateOf(initialProgressFraction)
    var shouldPlay by mutableStateOf(true)
    var resizeMode by mutableStateOf(playerSettingsUiState.resizeMode)
    /** Manual "Vertical Video" mode: while true the player holds a portrait orientation lock. */
    var manualVerticalVideoMode by mutableStateOf(false)
    /** True while the current stream's reported dimensions are vertical (height > width). */
    var autoDetectedVerticalVideo by mutableStateOf(false)
        private set
    /**
     * Set when the user turns the Vertical Video button off while auto-detection is active.
     * Suppresses auto portrait for the rest of the current playback item; cleared on item change.
     */
    var verticalVideoAutoOptOut by mutableStateOf(false)
        private set
    /** Portrait lock is held when the user forced it, or auto-detection fired and wasn't opted out. */
    val isPortraitLocked: Boolean
        get() = manualVerticalVideoMode || (autoDetectedVerticalVideo && !verticalVideoAutoOptOut)

    /**
     * Updates auto-detection from the engine's reported video size. Unknown (0) dimensions are
     * ignored so a transient 0x0 snapshot mid-playback doesn't flip orientation back and forth;
     * [resetAutoVerticalDetection] clears the state when the playback item changes.
     */
    fun updateAutoVerticalDetection(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        autoDetectedVerticalVideo = height > width
    }

    fun resetAutoVerticalDetection() {
        autoDetectedVerticalVideo = false
        verticalVideoAutoOptOut = false
    }

    /** Explicit user opt-out: turns off both the manual lock and auto-detection for this item. */
    fun disableVerticalVideoMode() {
        manualVerticalVideoMode = false
        verticalVideoAutoOptOut = autoDetectedVerticalVideo || verticalVideoAutoOptOut
    }

    /**
     * Toggles portrait lock on/off. Turning off while auto-detected sets [verticalVideoAutoOptOut]
     * so the current item stays unlocked even though the dimensions are still vertical.
     */
    fun toggleVerticalVideoMode() {
        if (isPortraitLocked) {
            disableVerticalVideoMode()
        } else {
            manualVerticalVideoMode = true
            verticalVideoAutoOptOut = false
        }
    }

    var layoutSize by mutableStateOf(IntSize.Zero)
    var playbackSnapshot by mutableStateOf(PlayerPlaybackSnapshot())
    var playbackSnapshotKey by mutableStateOf<PlaybackKey?>(null)
    var playerController by mutableStateOf<PlayerEngineController?>(null)
    var playerControllerSourceUrl by mutableStateOf<String?>(null)
    var errorMessage by mutableStateOf<String?>(null)
    var isScrubbingTimeline by mutableStateOf(false)
    var scrubbingPositionMs by mutableStateOf<Long?>(null)
    var pausedOverlayVisible by mutableStateOf(false)
    var gestureFeedback by mutableStateOf<GestureFeedbackState?>(null)
    var liveGestureFeedback by mutableStateOf<GestureFeedbackState?>(null)
    var renderedGestureFeedback by mutableStateOf<GestureFeedbackState?>(null)
    var lockedOverlayVisible by mutableStateOf(false)
    var gestureMessageJob by mutableStateOf<Job?>(null)
    var accumulatedSeekResetJob by mutableStateOf<Job?>(null)
    var seekProgressSyncJob by mutableStateOf<Job?>(null)
    var accumulatedSeekState by mutableStateOf<PlayerAccumulatedSeekState?>(null)
    var initialLoadCompleted by mutableStateOf(false)
    var speedBoostRestoreSpeed by mutableStateOf<Float?>(null)
    var isHoldToSpeedGestureActive by mutableStateOf(false)
    var initialSeekApplied by mutableStateOf(
        initialPositionMs <= 0L && ((initialProgressFraction ?: 0f) <= 0f),
    )
    var lastProgressPersistEpochMs by mutableStateOf(0L)
    var previousIsPlaying by mutableStateOf(false)
    var hasRequestedScrobbleStartForCurrentItem by mutableStateOf(false)
    var scrobbleStartRequestGeneration by mutableStateOf(0L)
    var pendingSeekScrobbleRestart by mutableStateOf(false)
    var hasSentCompletionScrobbleForCurrentItem by mutableStateOf(false)
    var currentTrackingMedia by mutableStateOf<TrackingMediaReference?>(null)

    var showSourcesPanel by mutableStateOf(false)
    var showEpisodesPanel by mutableStateOf(false)
    var showSubmitIntroModal by mutableStateOf(false)
    var submitIntroSegmentType by mutableStateOf("intro")
    var submitIntroStartTimeStr by mutableStateOf("00:00")
    var submitIntroEndTimeStr by mutableStateOf("00:00")
    var episodeStreamsPanelState by mutableStateOf(EpisodeStreamsPanelState())
    var playerMetaVideos by mutableStateOf<List<MetaVideo>>(emptyList())
    var playerMeta by mutableStateOf<MetaDetails?>(null)
    var skipIntervals by mutableStateOf<List<SkipInterval>>(emptyList())
    val autoSkippedIntervals = mutableSetOf<SkipInterval>()
    var lastManualSkipSeekPositions by mutableStateOf<Pair<Long, Long>?>(null)
    var activeSkipInterval by mutableStateOf<SkipInterval?>(null)
    var skipIntervalDismissed by mutableStateOf(false)
    var parentalWarnings by mutableStateOf<List<ParentalWarning>>(emptyList())
    var showParentalGuide by mutableStateOf(false)
    var parentalGuideHasShown by mutableStateOf(false)
    var playbackStartedForParentalGuide by mutableStateOf(false)
    var nextEpisodeInfo by mutableStateOf<NextEpisodeInfo?>(null)
    var showNextEpisodeCard by mutableStateOf(false)
    var nextEpisodeCardDismissed by mutableStateOf(false)
    var nextEpisodeAutoPlaySearching by mutableStateOf(false)
    var nextEpisodeAutoPlaySourceName by mutableStateOf<String?>(null)
    var nextEpisodeAutoPlayCountdown by mutableStateOf<Int?>(null)
    var nextEpisodeAutoPlayJob by mutableStateOf<Job?>(null)
    var nextEpisodeAutoPlayAutomatic by mutableStateOf(false)
    var nextEpisodePreloadJob by mutableStateOf<Job?>(null)
    var nextEpisodePreloadTriggered by mutableStateOf(false)
    var pendingP2pSwitch by mutableStateOf<PendingPlayerP2pSwitch?>(null)
    var credentialRefreshJob by mutableStateOf<Job?>(null)
    var credentialRefreshAttemptedSourceUrl by mutableStateOf<String?>(null)

    var showAudioModal by mutableStateOf(false)
    var showSubtitleModal by mutableStateOf(false)
    var showVideoSettingsModal by mutableStateOf(false)
    var showStreamInfo by mutableStateOf(false)
    var streamMediaInfo by mutableStateOf(PlayerMediaInfo())
    var playbackEngineOverride by mutableStateOf<AndroidPlaybackEngine?>(null)
    var audioTracks by mutableStateOf<List<AudioTrack>>(emptyList())
    var serverAudioTracks by mutableStateOf<List<AudioTrack>>(emptyList())
    var serverSubtitleTracks by mutableStateOf<List<SubtitleTrack>>(emptyList())
    var serverTrackSwitchJob by mutableStateOf<Job?>(null)
    var serverAudioPreferenceKey: String? = null
    var subtitleTracks by mutableStateOf<List<SubtitleTrack>>(emptyList())
    var selectedAudioIndex by mutableStateOf(-1)
    var selectedSubtitleIndex by mutableStateOf(-1)
    var selectedAddonSubtitleId by mutableStateOf<String?>(null)
    var useCustomSubtitles by mutableStateOf(false)
    var preferredAudioSelectionApplied by mutableStateOf(false)
    var appliedAudioPreferences: AppliedAudioPreferences? = null
    var preferredSubtitleSelectionApplied by mutableStateOf(false)
    var isUserExplicitAudioSelection by mutableStateOf(false)
    var isUserExplicitSubtitleSelection by mutableStateOf(false)
    var hasScannedTextTracksOnce by mutableStateOf(false)
    var autoFetchedAddonSubtitlesForKey by mutableStateOf<String?>(null)
    var trackPreferenceRestoreApplied by mutableStateOf(false)
    var subtitleDelayMs by mutableStateOf(0)
    var subtitleAutoSyncState by mutableStateOf(SubtitleAutoSyncUiState())

    var lastSyncedSettingsResizeMode: PlayerResizeMode? = null
    var lastResetPlaybackIdentity: PlaybackKey? = null
    var lastResetVideoIdentity: String? = null
}
