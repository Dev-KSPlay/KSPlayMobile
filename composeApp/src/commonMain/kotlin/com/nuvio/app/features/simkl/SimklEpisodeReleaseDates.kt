package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import com.nuvio.app.core.time.parseEpisodeReleaseEpochMs
import com.nuvio.app.core.time.parseZonedIsoDateTimeToEpochMs
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.player.skip.SimklIdResolver
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.parseTrackingExternalIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.time.Instant

private const val RELEASES_CACHE_TTL_MS = 6L * 60 * 60 * 1000
private const val FAILURE_CACHE_TTL_MS = 10L * 60 * 1000
private const val MAX_SOURCES_PER_SERIES = 6

internal data class SimklEpisodeSource(val path: String, val simklId: Long)

/** Uses the first Simkl availability date when an addon lists a later broadcast date. */
internal object SimklEpisodeReleaseDates {
    private val log = Logger.withTag("SimklReleaseDates")
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheMutex = Mutex()
    private val cache = mutableMapOf<SimklEpisodeSource, CachedReleases>()

    suspend fun withReleaseDates(meta: MetaDetails): MetaDetails {
        val now = EpisodeReleaseDatePlatform.nowEpochMs()
        if (SimklConfig.CLIENT_ID.isBlank() || !meta.videos.hasUpcomingEpisode(now)) return meta

        val contentIds = listOfNotNull(meta.id, meta.imdbId).distinct()
            .filter(::usesTvdbStyleNumbering)
        if (contentIds.isEmpty()) return meta

        return try {
            SimklSyncRepository.ensureLoaded()
            val sources = SimklSyncRepository.state.value.snapshot.episodeSourcesFor(contentIds)
                .ifEmpty { resolveEpisodeSources(contentIds) }
            if (sources.isEmpty()) return meta

            val releases = mutableMapOf<Pair<Int, Int>, Long>()
            for (source in sources) {
                for ((key, released) in releasesFor(source, now)) {
                    val known = releases[key]
                    if (known == null || released < known) releases[key] = released
                }
            }
            meta.copy(videos = meta.videos.withEarlierSimklReleases(releases))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w { "Failed to load Simkl release dates for ${meta.id}: ${error.message}" }
            meta
        }
    }

    private suspend fun releasesFor(source: SimklEpisodeSource, now: Long): Map<Pair<Int, Int>, Long> {
        cacheMutex.withLock { cache[source]?.takeIf { now < it.expiresAtEpochMs } }
            ?.let { return it.releases }

        val releases = try {
            val response = SimklApi.client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.GET,
                    path = "/${source.path}/episodes/${source.simklId}",
                    query = mapOf("extended" to "full"),
                    requiresAuthentication = false,
                    retryPolicy = SimklRetryPolicy.NEVER,
                ),
            )
            parseSimklEpisodeReleases(response.body, isAnime = source.path == "anime", json = json)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w { "Failed to fetch Simkl episodes ${source.path}/${source.simklId}: ${error.message}" }
            null
        }
        cacheMutex.withLock {
            cache[source] = CachedReleases(
                releases = releases.orEmpty(),
                expiresAtEpochMs = now + if (releases == null) FAILURE_CACHE_TTL_MS else RELEASES_CACHE_TTL_MS,
            )
        }
        return releases.orEmpty()
    }

    private suspend fun resolveEpisodeSources(contentIds: List<String>): List<SimklEpisodeSource> {
        val imdbId = contentIds.firstNotNullOfOrNull { parseTrackingExternalIds(it).imdb } ?: return emptyList()
        val resolved = SimklIdResolver.resolveIds("imdb", imdbId) ?: return emptyList()
        val path = resolved.type.takeIf { it == "anime" || it == "tv" } ?: return emptyList()
        return listOf(SimklEpisodeSource(path, resolved.simklId))
    }

    private data class CachedReleases(
        val releases: Map<Pair<Int, Int>, Long>,
        val expiresAtEpochMs: Long,
    )
}

private fun usesTvdbStyleNumbering(contentId: String): Boolean =
    parseTrackingExternalIds(contentId).let { ids ->
        !ids.imdb.isNullOrBlank() || ids.tmdb != null || !ids.tvdb.isNullOrBlank()
    }

private fun List<MetaVideo>.hasUpcomingEpisode(now: Long): Boolean = any { video ->
    video.season != null && video.episode != null &&
        (parseEpisodeReleaseEpochMs(video.released)?.let { it > now } == true)
}

private fun TrackingExternalIds.sharesSeriesIdWith(other: TrackingExternalIds): Boolean =
    (!imdb.isNullOrBlank() && imdb.equals(other.imdb, ignoreCase = true)) ||
        (tmdb != null && tmdb == other.tmdb) ||
        (!tvdb.isNullOrBlank() && tvdb.equals(other.tvdb, ignoreCase = true))

/** Anime seasons may be separate Simkl entries with the same franchise IDs. */
internal fun SimklSyncSnapshot.episodeSourcesFor(contentIds: List<String>): List<SimklEpisodeSource> {
    val wanted = contentIds.map(::parseTrackingExternalIds)
    return entries.asSequence()
        .filter { !it.isMovieEntry() }
        .mapNotNull { entry ->
            val ids = entry.media?.toTrackingExternalIds() ?: return@mapNotNull null
            if (wanted.none { it.sharesSeriesIdWith(ids) }) return@mapNotNull null
            val simklId = ids.simkl?.takeIf { it > 0L } ?: return@mapNotNull null
            SimklEpisodeSource(if (entry.mediaType == SimklMediaType.ANIME) "anime" else "tv", simklId)
        }
        .distinct()
        .sortedByDescending(SimklEpisodeSource::simklId)
        .take(MAX_SOURCES_PER_SERIES)
        .toList()
}

/** Anime uses its TVDB season/episode mapping; ordinary shows use their native numbering. */
internal fun parseSimklEpisodeReleases(
    payload: String,
    isAnime: Boolean,
    json: Json = Json { ignoreUnknownKeys = true },
): Map<Pair<Int, Int>, Long> {
    val episodes = json.parseToJsonElement(payload) as? JsonArray ?: return emptyMap()
    val releases = mutableMapOf<Pair<Int, Int>, Long>()
    for (element in episodes) {
        val episode = element as? JsonObject ?: continue
        if (episode.string("type")?.lowercase() != "episode") continue
        val numbering = if (isAnime) episode["tvdb"] as? JsonObject ?: continue else episode
        val season = numbering.int("season")?.takeIf { it > 0 } ?: continue
        val number = numbering.int("episode")?.takeIf { it > 0 } ?: continue
        val released = episode.string("date")?.let(::parseZonedIsoDateTimeToEpochMs) ?: continue
        val key = season to number
        val known = releases[key]
        if (known == null || released < known) releases[key] = released
    }
    return releases
}

internal fun List<MetaVideo>.withEarlierSimklReleases(releases: Map<Pair<Int, Int>, Long>): List<MetaVideo> =
    map { video ->
        val key = video.season?.let { season -> video.episode?.let { episode -> season to episode } }
        val simklRelease = key?.let(releases::get)
        val addonRelease = parseEpisodeReleaseEpochMs(video.released)
        if (simklRelease != null && addonRelease != null && simklRelease < addonRelease) {
            video.copy(released = Instant.fromEpochMilliseconds(simklRelease).toString())
        } else {
            video
        }
    }

private fun JsonObject.string(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.trim()?.takeIf(String::isNotBlank)

private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
