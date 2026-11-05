package com.animeverse

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * One member site of the AnimeVerse addon. New sites/apps join by
 * adding a file that implements this interface and one entry in
 * [AnimeVerseProvider.sites] — no changes to the provider itself.
 *
 * v16 members: Miruro only. AnimoHub was a member in v13–v15
 * and was REMOVED 2026-11-05 at the user's order ("مصدر ضعيف"):
 * Hindi-heavy movie files, series metadata-only with no
 * episode play links — its file is deleted from the repo.
 * Anilab2 is NOT a member yet: its catalog API is verified but
 * its streams live behind the KYOTO Player API
 * (Cloudflare-challenged, episode path uncracked). It joins only
 * after its full playback chain is traced live.
 */
interface AnimeSite {
    /** Host substring used to route load()/loadLinks() URLs, e.g. "miruro.tv". */
    val host: String

    /** Home rows: (request data, row name). Data is site-specific. */
    val sections: List<Pair<String, String>>

    /** Cards for one home row. */
    suspend fun homeSection(api: MainAPI, data: String): List<SearchResponse>

    /** Search cards. */
    suspend fun search(api: MainAPI, query: String): List<SearchResponse>

    /** Detail page -> series (episodes) or movie. */
    suspend fun load(api: MainAPI, url: String): LoadResponse

    /** Watch page -> direct streams + subtitles. */
    suspend fun loadLinks(
        api: MainAPI,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean
}
