package com.reanime

import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * reanime.cz public JSON API (no key). Rewritten from raghav ReAnime v9:
 * org.json instead of a third-party JSON mapper, no remote domain lookup (fixed mainUrl),
 * no coroutine Mutex (plain synchronized cursor map).
 */
object ReAnimeApi {

    const val FLIX_EMBED_BASE = "https://flixcloud.cc"

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    val BROWSER_HEADERS: Map<String, String>
        get() = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json",
            "Referer" to "https://reanime.cz/"
        )

    private val homeCursors = mutableMapOf<String, String?>()

    // ---------- models (manual org.json parsing) ----------

    class Title(val english: String?, val romaji: String?, val native: String?, val userPreferred: String?) {
        fun display(): String? =
            english?.takeIf { it.isNotBlank() } ?: romaji?.takeIf { it.isNotBlank() }
                ?: native?.takeIf { it.isNotBlank() } ?: userPreferred?.takeIf { it.isNotBlank() }

        companion object {
            fun parse(o: JSONObject?): Title? {
                if (o == null) return null
                return Title(
                    o.optString("english").ifEmpty { null },
                    o.optString("romaji").ifEmpty { null },
                    o.optString("native").ifEmpty { null },
                    o.optString("user_preferred").ifEmpty { null }
                )
            }
        }
    }

    class CoverImage(val extraLarge: String?, val large: String?, val medium: String?) {
        fun best(): String? = extraLarge ?: large ?: medium

        companion object {
            fun parse(o: JSONObject?): CoverImage? {
                if (o == null) return null
                return CoverImage(
                    o.optString("extra_large").ifEmpty { null },
                    o.optString("large").ifEmpty { null },
                    o.optString("medium").ifEmpty { null }
                )
            }
        }
    }

    class SearchItem(
        val animeId: String?,
        val title: Title?,
        val coverImage: CoverImage?,
        val seasonYear: Int?,
        val subbed: Int?,
        val dubbed: Int?,
        val averageScore: Int?
    ) {
        companion object {
            fun parse(o: JSONObject): SearchItem {
                return SearchItem(
                    o.optString("anime_id").ifEmpty { null },
                    Title.parse(o.optJSONObject("title")),
                    CoverImage.parse(o.optJSONObject("cover_image")),
                    if (o.has("season_year") && !o.isNull("season_year")) o.optInt("season_year") else null,
                    if (o.has("subbed") && !o.isNull("subbed")) o.optInt("subbed") else null,
                    if (o.has("dubbed") && !o.isNull("dubbed")) o.optInt("dubbed") else null,
                    if (o.has("average_score") && !o.isNull("average_score")) o.optInt("average_score") else null
                )
            }

            fun parseList(arr: JSONArray?): List<SearchItem> {
                val out = ArrayList<SearchItem>()
                if (arr == null) return out
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(parse(o))
                }
                return out
            }
        }
    }

    class AnimeDetail(
        val anilistId: Int?,
        val malId: Int?,
        val themoviedbId: Int?,
        val externalTmdbSeason: Int?,
        val title: Title?,
        val coverImage: CoverImage?,
        val bannerImage: String?,
        val description: String?,
        val format: String?,
        val status: String?,
        val genres: List<String>?,
        val tags: List<String>?,
        val seasonYear: Int?,
        val duration: Int?,
        val averageScore: Int?
    ) {
        companion object {
            fun parse(o: JSONObject): AnimeDetail {
                val genresArr = o.optJSONArray("genres")
                val genres = ArrayList<String>()
                if (genresArr != null) for (i in 0 until genresArr.length()) {
                    genresArr.optString(i).takeIf { it.isNotBlank() }?.let { genres.add(it) }
                }
                val tagsArr = o.optJSONArray("tags")
                val tags = ArrayList<String>()
                if (tagsArr != null) for (i in 0 until tagsArr.length()) {
                    tagsArr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { tags.add(it) }
                }
                val ext = o.optJSONObject("external_seasons")
                return AnimeDetail(
                    if (o.has("anilist_id") && !o.isNull("anilist_id")) o.optInt("anilist_id") else null,
                    if (o.has("mal_id") && !o.isNull("mal_id")) o.optInt("mal_id") else null,
                    if (o.has("themoviedb_id") && !o.isNull("themoviedb_id")) o.optInt("themoviedb_id") else null,
                    if (ext != null && ext.has("tmdb") && !ext.isNull("tmdb")) ext.optInt("tmdb") else null,
                    Title.parse(o.optJSONObject("title")),
                    CoverImage.parse(o.optJSONObject("cover_image")),
                    o.optString("banner_image").ifEmpty { null },
                    o.optString("description").ifEmpty { null },
                    o.optString("format").ifEmpty { null },
                    o.optString("status").ifEmpty { null },
                    genres.ifEmpty { null },
                    tags.ifEmpty { null },
                    if (o.has("season_year") && !o.isNull("season_year")) o.optInt("season_year") else null,
                    if (o.has("duration") && !o.isNull("duration")) o.optInt("duration") else null,
                    if (o.has("average_score") && !o.isNull("average_score")) o.optInt("average_score") else null
                )
            }
        }
    }

    class EpisodeEntry(
        val episodeNumber: Int?,
        val title: String?,
        val description: String?,
        val thumbnail: String?,
        val aired: String?,
        val subbed: Boolean?,
        val dubbed: Boolean?
    ) {
        companion object {
            fun parseList(arr: JSONArray?): List<EpisodeEntry> {
                val out = ArrayList<EpisodeEntry>()
                if (arr == null) return out
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(
                        EpisodeEntry(
                            if (o.has("episode_number") && !o.isNull("episode_number")) o.optInt("episode_number") else null,
                            o.optString("title").ifEmpty { null },
                            o.optString("description").ifEmpty { null },
                            o.optString("thumbnail").ifEmpty { null },
                            o.optString("aired").ifEmpty { null },
                            if (o.has("subbed") && !o.isNull("subbed")) o.optBoolean("subbed") else null,
                            if (o.has("dubbed") && !o.isNull("dubbed")) o.optBoolean("dubbed") else null
                        )
                    )
                }
                return out
            }
        }
    }

    class FlixServer(
        val serverName: String?,
        val dataLink: String?,
        val softsub: Boolean?,
        val hardsub: Boolean?
    ) {
        companion object {
            fun parseList(arr: JSONArray?): List<FlixServer> {
                val out = ArrayList<FlixServer>()
                if (arr == null) return out
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(
                        FlixServer(
                            o.optString("serverName").ifEmpty { null },
                            o.optString("dataLink").ifEmpty { null },
                            if (o.has("softsub") && !o.isNull("softsub")) o.optBoolean("softsub") else null,
                            if (o.has("hardsub") && !o.isNull("hardsub")) o.optBoolean("hardsub") else null
                        )
                    )
                }
                return out
            }
        }
    }

    // ---------- transport ----------

    private suspend fun getJson(url: String, headers: Map<String, String> = BROWSER_HEADERS): String? = try {
        val resp = app.get(url, headers = headers)
        if (resp.isSuccessful) resp.text else null
    } catch (_: Exception) {
        null
    }

    suspend fun search(query: String, page: Int, limit: Int = 40): List<SearchItem> {
        val offset = (page - 1) * limit
        val body = getJson("https://reanime.cz/api/v1/search?q=${urlEncode(query)}&limit=$limit&offset=$offset")
            ?: return emptyList()
        return try {
            SearchItem.parseList(JSONObject(body).optJSONArray("results"))
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun searchSorted(sort: String, page: Int, limit: Int = 40): List<SearchItem> {
        val offset = (page - 1) * limit
        val body = getJson("https://reanime.cz/api/v1/search?limit=$limit&offset=$offset&sort=$sort")
            ?: return emptyList()
        return try {
            SearchItem.parseList(JSONObject(body).optJSONArray("results"))
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun homeSection(section: String, page: Int, limit: Int = 40): Pair<List<SearchItem>, Boolean> {
        val cursor = synchronized(homeCursors) {
            if (page <= 1) null else homeCursors["$section-$page"]
        }
        val sep = if (cursor == null) "?" else "&cursor=${urlEncode(cursor)}"
        val body = getJson("https://reanime.cz/api/v1/home/$section?limit=$limit$sep")
            ?: return Pair(emptyList(), false)
        return try {
            val env = JSONObject(body)
            val items = SearchItem.parseList(env.optJSONArray("data"))
            val nextCursor = env.optString("next_cursor").ifEmpty { null }
            if (nextCursor != null) {
                synchronized(homeCursors) { homeCursors["$section-${page + 1}"] = nextCursor }
            }
            items to (env.optBoolean("has_more", false))
        } catch (_: Exception) {
            Pair(emptyList(), false)
        }
    }

    suspend fun animeDetail(slug: String): AnimeDetail? {
        val body = getJson("https://reanime.cz/api/v1/anime/${urlEncode(slug)}") ?: return null
        return try {
            AnimeDetail.parse(JSONObject(body))
        } catch (_: Exception) {
            null
        }
    }

    suspend fun episodes(slug: String): List<EpisodeEntry> {
        val body = getJson("https://reanime.cz/api/v1/anime/${urlEncode(slug)}/episodes?limit=2000")
            ?: return emptyList()
        return try {
            EpisodeEntry.parseList(JSONObject(body).optJSONArray("data"))
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun flixServers(
        anilistId: Int?,
        tmdbId: Int?,
        season: Int?,
        episode: Int
    ): List<FlixServer> {
        val url = when {
            anilistId != null && anilistId > 0 ->
                "https://reanime.cz/api/flix/$anilistId/$episode"
            tmdbId != null && tmdbId > 0 ->
                "https://reanime.cz/api/flix/0/$episode?tmdb=$tmdbId&season=${season ?: 1}"
            else -> return emptyList()
        }
        val body = getJson(url, BROWSER_HEADERS + mapOf("Referer" to "https://reanime.cz/watch/"))
            ?: return emptyList()
        return try {
            val res = JSONObject(body)
            if (!res.optBoolean("success", false)) return emptyList()
            FlixServer.parseList(res.optJSONArray("servers"))
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")
}
