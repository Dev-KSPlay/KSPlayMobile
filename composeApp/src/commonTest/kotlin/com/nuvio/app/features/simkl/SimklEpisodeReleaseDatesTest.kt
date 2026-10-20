package com.nuvio.app.features.simkl

import com.nuvio.app.core.time.parseEpisodeReleaseEpochMs
import com.nuvio.app.features.details.MetaVideo
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class SimklEpisodeReleaseDatesTest {
    @Test
    fun animeUsesTvdbNumberingAndEarliestDuplicate() {
        val releases = parseSimklEpisodeReleases(
            payload = """[
                {"type":"episode","episode":2,"date":"2026-10-04T23:30:00+09:00","tvdb":{"season":1,"episode":2}},
                {"type":"episode","episode":2,"date":"2026-10-05T23:30:00+09:00","tvdb":{"season":1,"episode":2}},
                {"type":"episode","episode":3,"date":"2026-10-11T23:30:00+09:00","tvdb":{"season":1,"episode":3}},
                {"type":"episode","episode":4,"date":"2026-10-25","tvdb":{"season":1,"episode":4}},
                {"type":"special","episode":5,"date":"2026-10-01T00:00:00Z","tvdb":{"season":1,"episode":5}}
            ]""".trimIndent(),
            isAnime = true,
        )

        assertEquals(
            mapOf(
                (1 to 2) to parseEpisodeReleaseEpochMs("2026-10-04T14:30:00Z"),
                (1 to 3) to parseEpisodeReleaseEpochMs("2026-10-11T14:30:00Z"),
            ),
            releases,
        )
    }

    @Test
    fun televisionUsesNativeSeasonAndEpisodeWithUtcOffset() {
        val releases = parseSimklEpisodeReleases(
            payload = """[
                {"type":"episode","season":1,"episode":9,"date":"2023-03-12T21:00:00-04:00"},
                {"type":"special","season":1,"episode":10,"date":"2023-03-13T21:00:00-04:00"},
                {"type":"episode","season":2,"episode":1,"date":"2025-04-20"}
            ]""".trimIndent(),
            isAnime = false,
        )

        assertEquals(
            mapOf((1 to 9) to parseEpisodeReleaseEpochMs("2023-03-13T01:00:00Z")),
            releases,
        )
    }

    @Test
    fun earlierAvailabilityReplacesBroadcastDateWithoutChangingOtherEpisodes() {
        val videos = listOf(
            video(1, 2, "2026-10-09T00:00:00Z"),
            video(1, 3, "2026-10-16"),
            video(1, 4, "2026-10-18T00:00:00Z"),
            video(1, 5, null),
        )
        val releases = mapOf(
            (1 to 2) to requireNotNull(parseEpisodeReleaseEpochMs("2026-10-04T14:30:00Z")),
            (1 to 3) to requireNotNull(parseEpisodeReleaseEpochMs("2026-10-11T14:30:00Z")),
            (1 to 4) to requireNotNull(parseEpisodeReleaseEpochMs("2026-10-20T00:00:00Z")),
            (1 to 5) to requireNotNull(parseEpisodeReleaseEpochMs("2026-10-01T00:00:00Z")),
        )

        val corrected = videos.withEarlierSimklReleases(releases)
        assertEquals(releases[1 to 2], parseEpisodeReleaseEpochMs(corrected[0].released))
        assertEquals(releases[1 to 3], parseEpisodeReleaseEpochMs(corrected[1].released))
        assertEquals(videos.drop(2), corrected.drop(2))
    }

    @Test
    fun libraryLookupIncludesSeparateAnimeSeasonsAndSkipsMovies() {
        val snapshot = SimklSyncSnapshot(
            entries = listOf(
                entry(SimklMediaType.ANIME, 1372805, "tt13196080"),
                entry(SimklMediaType.ANIME, 2600001, "tt13196080"),
                entry(SimklMediaType.MOVIES, 99, "tt13196080"),
            ),
        )

        assertEquals(
            listOf(SimklEpisodeSource("anime", 2600001), SimklEpisodeSource("anime", 1372805)),
            snapshot.episodeSourcesFor(listOf("tt13196080")),
        )
    }

    private fun video(season: Int, episode: Int, released: String?): MetaVideo = MetaVideo(
        id = "tt0000000:$season:$episode",
        title = "Episode $episode",
        season = season,
        episode = episode,
        released = released,
    )

    private fun entry(type: SimklMediaType, simklId: Long, imdbId: String): SimklLibraryEntry {
        val media = SimklMedia(ids = buildJsonObject {
            put("simkl", simklId)
            put("imdb", imdbId)
        })
        return SimklLibraryEntry(
            mediaType = type,
            show = media.takeIf { type != SimklMediaType.MOVIES },
            movie = media.takeIf { type == SimklMediaType.MOVIES },
        )
    }
}
