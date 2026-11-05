package com.animeverse

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * AnimeVerse — multi-site anime addon for CloudStream.
 *
 * v16: Miruro.tv only. AnimoHub was REMOVED 2026-11-05 at the
 * user's order ("مصدر ضعيف" — weak source: Hindi-heavy files,
 * series metadata-only, no episode streams) — unregistered
 * here and its file deleted from the repo. New sites/apps join
 * via [AnimeSite]: add the site file, append it to [sites],
 * done — search aggregates across members, load/loadLinks
 * route by URL host, and each member's home rows appear under
 * its own section names.
 *
 * Standing rule: a member ships only after its full playback chain
 * is traced live (home/search/detail/streams on a real title).
 * Anilab2 is therefore NOT in v16 (see AnimeSite.kt).
 */
class AnimeVerseProvider : MainAPI() {
    override var mainUrl = "https://miruro.tv"
    override var name = "AnimeVerse"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    // Site registry — append new members here as they verify.
    private val sites: List<AnimeSite> = listOf(MiruroSite)

    override val mainPage = mainPageOf(
        *sites.flatMap { site -> site.sections }.toTypedArray()
    )

    private fun siteFor(url: String): AnimeSite? {
        return sites.firstOrNull { url.contains(it.host) }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val site = sites.firstOrNull { site ->
            site.sections.any { it.first == request.data }
        }
        val items = try {
            site?.homeSection(this, request.data) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        for (site in sites) {
            try {
                for (item in site.search(this, query)) {
                    if (seen.add(item.url)) out.add(item)
                }
            } catch (e: Exception) {
                // One dead member must not kill the rest.
            }
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse {
        val site = siteFor(url)
            ?: throw Error("AnimeVerse: no member site handles $url")
        return site.load(this, url)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val site = siteFor(data) ?: return false
        return site.loadLinks(this, data, subtitleCallback, callback)
    }
}
