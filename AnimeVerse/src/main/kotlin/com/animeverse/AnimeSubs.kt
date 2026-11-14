package com.animeverse

import com.lagradost.cloudstream3.TrailerData
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import java.net.URLEncoder

/**
 * Shared Arabic-subtitle helpers for AnimeVerse members.
 *
 * Extracted (unchanged logic) from MiruroSite v3-v12 so new members
 * inherit the same subtitle layer without duplicating it — the
 * addon-level layer the user ordered ("طبقة الترجمة على كل"):
 * IMDb suggest (no key) resolves a title to a tt id, then the
 * OpenSubtitles v3 Strem endpoint serves the subtitles:
 * /subtitles/series/<tt>:<season>:<episode>.json (episodes) or
 * /subtitles/movie/<tt>.json (films). Arabic = first ara file,
 * UTF-8 preferred. The merged AR+EN file is built by the relay
 * (GET /relay/mergesubs) from the resolved public URLs; the
 * auto-translate fallback lives in AnimeTranslate (phone-side
 * gtx + MyMemory, cached) — the old gtx-only translateSubCues
 * was deleted in v21 (it threw on the phone, Panel #10).
 * MiruroSite still carries its own copies (v12 verified) — it
 * migrates to this object in a later cleanup, not in v13.
 */
object AnimeSubs {
    const val relayBase = "https://anime-subs-relay.mysdhj00-del.deno.net"

    suspend fun suggestImdbId(query: String, wantSeries: Boolean): String? {
        return try {
            val bucket = query.firstOrNull()?.lowercaseChar() ?: return null
            val q = URLEncoder.encode(query, "UTF-8")
            val text = app.get("https://v2.sg.media-imdb.com/suggestion/$bucket/$q.json").text
            val arr = org.json.JSONObject(text).optJSONArray("d") ?: return null
            var fallback: String? = null
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (!id.startsWith("tt")) continue
                if (fallback == null) fallback = id
                val kind = o.optString("q")
                if (wantSeries && kind == "TV series") return id
                if (!wantSeries && kind == "feature") return id
            }
            fallback
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun osSubUrls(title: String, season: Int, episode: Int?, lang: String): List<String> {
        return try {
            if (title.isEmpty()) return emptyList()
            val tt = suggestImdbId(title, episode != null) ?: return emptyList()
            val url = if (episode != null)
                "https://opensubtitles-v3.strem.io/subtitles/series/$tt:$season:$episode.json"
            else
                "https://opensubtitles-v3.strem.io/subtitles/movie/$tt.json"
            val text = app.get(url).text
            val arr = org.json.JSONObject(text).optJSONArray("subtitles") ?: return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("lang") != lang) continue
                val u = o.optString("url")
                if (u.isBlank() || out.contains(u)) continue
                out.add(u)
                if (out.size >= 5) break
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** All OpenSubtitles URLs for the title in a language (cap 5). */
    suspend fun osSubsFor(title: String, season: Int, episode: Int?, lang: String): List<String> {
        return osSubUrls(title, season, episode, lang)
    }

    /** One OpenSubtitles track in a language, UTF-8 preferred. */
    suspend fun osSubFor(title: String, season: Int, episode: Int?, lang: String): String? {
        return try {
            if (title.isEmpty()) return null
            val tt = suggestImdbId(title, episode != null) ?: return null
            val url = if (episode != null)
                "https://opensubtitles-v3.strem.io/subtitles/series/$tt:$season:$episode.json"
            else
                "https://opensubtitles-v3.strem.io/subtitles/movie/$tt.json"
            val text = app.get(url).text
            val arr = org.json.JSONObject(text).optJSONArray("subtitles") ?: return null
            var firstLang: String? = null
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("lang") != lang) continue
                val u = o.optString("url")
                if (u.isBlank()) continue
                if (firstLang == null) firstLang = u
                if (o.optString("SubEncoding") == "UTF-8") return u
            }
            firstLang
        } catch (e: Exception) {
            null
        }
    }

    // ---------- subtitle content: parse / merge / repair ----------

    class SubCue(
        val startMs: Long,
        val endMs: Long,
        val start: String,
        val end: String,
        val text: String
    )

    fun subTsToMs(t: String): Long {
        val clean = t.trim().replace(',', '.')
        val parts = clean.split(":")
        val secParts = parts.last().split(".")
        val secs = secParts[0].toLongOrNull() ?: 0L
        val ms = (secParts.getOrNull(1) ?: "0").padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        var total = secs * 1000 + ms
        if (parts.size == 3) total += parts[0].toLongOrNull()?.times(3600000) ?: 0L
        if (parts.size >= 2) total += parts[parts.size - 2].toLongOrNull()?.times(60000) ?: 0L
        return total
    }

    fun subFmtMs(ms: Long): String {
        val h = ms / 3600000
        val m = (ms % 3600000) / 60000
        val s = (ms % 60000) / 1000
        val r = ms % 1000
        return "%02d:%02d:%02d.%03d".format(h, m, s, r)
    }

    fun parseSubCues(text: String): List<SubCue> {
        val norm = text.replace("\r\n", "\n").replace('\r', '\n')
        val tsRe = Regex("""(\d{1,2}:\d{2}(?::\d{2})?[.,]\d{3})\s*-->\s*(\d{1,2}:\d{2}(?::\d{2})?[.,]\d{3})""")
        val out = ArrayList<SubCue>()
        for (block in norm.split(Regex("""\n\s*\n"""))) {
            val lines = block.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            if (lines[0].startsWith("WEBVTT", ignoreCase = true)) continue
            if (lines[0].startsWith("NOTE") || lines[0].startsWith("STYLE") || lines[0].startsWith("REGION")) continue
            val tsIdx = lines.indexOfFirst { tsRe.containsMatchIn(it) }
            if (tsIdx < 0) continue
            val m = tsRe.find(lines[tsIdx]) ?: continue
            val body = cleanSubText(lines.drop(tsIdx + 1).joinToString("\n"))
            if (body.isEmpty()) continue
            val sMs = subTsToMs(m.groupValues[1])
            val eMs = subTsToMs(m.groupValues[2])
            out.add(SubCue(sMs, eMs, subFmtMs(sMs), subFmtMs(eMs), body))
        }
        return out
    }

    fun buildSubVTT(cues: List<SubCue>, texts: List<String>? = null): String {
        val sb = StringBuilder("WEBVTT\n\n")
        for ((i, c) in cues.withIndex()) {
            sb.append(c.start).append(" --> ").append(c.end).append("\n")
            sb.append(texts?.getOrNull(i) ?: c.text).append("\n\n")
        }
        return sb.toString()
    }

    fun mergeSubVTT(engText: String, araText: String): String {
        val eng = parseSubCues(engText)
        val ara = parseSubCues(araText)
        val used = HashSet<Int>()
        val merged = ArrayList<SubCue>()
        for (e in eng) {
            val parts = ArrayList<String>()
            for ((i, a) in ara.withIndex()) {
                if (i in used) continue
                if (a.startMs < e.endMs && a.endMs > e.startMs) {
                    used.add(i)
                    parts.add(a.text)
                }
            }
            val text = if (parts.isEmpty()) e.text else parts.joinToString(" ") + "\n" + e.text
            merged.add(SubCue(e.startMs, e.endMs, e.start, e.end, text))
        }
        for ((i, a) in ara.withIndex()) {
            if (i !in used) merged.add(a)
        }
        merged.sortBy { it.startMs }
        return buildSubVTT(merged)
    }

    private val win1252Special = mapOf(
        '€' to 0x80, '‚' to 0x82, 'ƒ' to 0x83, '„' to 0x84, '…' to 0x85, '†' to 0x86, '‡' to 0x87,
        'ˆ' to 0x88, '‰' to 0x89, 'Š' to 0x8A, '‹' to 0x8B, 'Œ' to 0x8C, 'Ž' to 0x8E,
        '‘' to 0x91, '’' to 0x92, '“' to 0x93, '”' to 0x94, '•' to 0x95, '–' to 0x96, '—' to 0x97,
        '˜' to 0x98, '™' to 0x99, 'š' to 0x9A, '›' to 0x9B, 'œ' to 0x9C, 'ž' to 0x9E, 'Ÿ' to 0x9F
    )

    fun fixSubEncoding(str: String): String {
        val out = StringBuilder()
        var run = StringBuilder()
        fun flush() {
            if (run.isEmpty()) return
            val text = run.toString()
            run = StringBuilder()
            try {
                val bytes = ByteArray(text.length)
                for ((i, c) in text.withIndex()) {
                    val code = c.code
                    bytes[i] = when {
                        code < 0x100 -> code.toByte()
                        win1252Special.containsKey(c) -> win1252Special.getValue(c).toByte()
                        else -> { out.append(text); return }
                    }
                }
                val fixed = String(bytes, Charsets.UTF_8)
                if (fixed.contains('\uFFFD')) out.append(text)
                else if (fixed.any { it in '\u0600'..'\u06FF' }) out.append(fixed)
                else out.append(text)
            } catch (e: Exception) {
                out.append(text)
            }
        }
        for (c in str) {
            if (c.code < 0x100 || win1252Special.containsKey(c)) run.append(c)
            else { flush(); out.append(c) }
        }
        flush()
        return out.toString()
    }

    fun cleanSubText(t: String): String {
        return fixSubEncoding(
            t.replace(Regex("""\{[^}]*\}"""), "")
                .replace("\\N", " ")
                .replace("\\n", " ")
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
        ).trim()
    }

    /** Fetches a subtitle file; counts only when it parses into real cues. */
    suspend fun fetchSubText(url: String, referer: String, killer: CloudflareKiller?): Pair<String, Int> {
        val plainHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
            "Referer" to referer,
            "Accept" to "*/*"
        )
        var rawLen = 0
        for (useKiller in listOf(false, true)) {
            try {
                val text = if (useKiller && killer != null)
                    app.get(url, headers = plainHeaders, interceptor = killer).text
                else app.get(url, headers = plainHeaders).text
                rawLen = text.length
                if (text.isNotEmpty() && parseSubCues(text).isNotEmpty()) return text to rawLen
            } catch (e: Exception) {
            }
        }
        return "" to rawLen
    }

    /** Hosts a finished VTT on the relay and returns its https URL. */
    suspend fun storeSub(vtt: String): String? {
        return try {
            val res = app.post("$relayBase/relay/store", data = mapOf("vtt" to vtt))
            val url = org.json.JSONObject(res.text).optString("url")
            url.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    // Trailer (rollout, pattern from HorrorMovies v11): a trailer
    // button on every detail page, from a free YouTube search.
    // Desktop UA is REQUIRED — the mobile search page returns no
    // parseable results. The setter wants a MutableList. Anime:
    // "PV" titles count as trailers too.
    val trailerHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    fun latinTitleCandidates(title: String): List<String> {
        var t = title.replace(Regex("""[\u0600-\u06FF]+"""), " ")
        t = t.replace(Regex("""(?i)\b(mtarjm|mudblj|online|web-dl|bluray|hd|hdts|hdcam|webdl)\b"""), " ")
        t = t.replace(Regex("""[^A-Za-z0-9 :'\-!.,]+"""), " ")
        t = t.replace(Regex("""\s+"""), " ").trim()
        t = t.replace(Regex("""\s+(19|20)\d\d\s*$"""), "").trim()
        if (t.isBlank()) return emptyList()
        val out = mutableListOf(t)
        Regex("""\s+\d{1,2}$""").find(t)?.let {
            val stripped = t.substring(0, it.range.first).trim()
            if (stripped.isNotBlank()) out += stripped
        }
        return out
    }

    fun cleanTrailerTitle(title: String): String =
        title.replace(Regex("""(?i)\b(مشاهدة|مترجم|مدبلج|اون لاين|اوفلاين)\b"""), " ")
            .replace(Regex("""\s+"""), " ").trim()

    suspend fun trailerFor(
        title: String,
        isSeries: Boolean = false,
        acceptPv: Boolean = false
    ): MutableList<TrailerData> {
        return try {
            val year = Regex("""(19|20)\d\d""").find(title)?.value
            val queries = ArrayList<String>()
            for (cand in latinTitleCandidates(title)) {
                queries += if (isSeries) "$cand official trailer"
                else listOfNotNull(cand, year, "official trailer").joinToString(" ")
            }
            if (queries.isEmpty()) {
                val clean = cleanTrailerTitle(title)
                if (clean.isNotBlank()) {
                    queries += if (isSeries) "$clean الإعلان الرسمي"
                    else listOfNotNull(clean, year, "trailer").joinToString(" ")
                }
            }
            for (q in queries) {
                val id = youtubeTrailerId(q, acceptPv) ?: continue
                return mutableListOf(
                    TrailerData(
                        "https://www.youtube.com/watch?v=$id",
                        "https://www.youtube.com/",
                        false
                    )
                )
            }
            mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    suspend fun youtubeTrailerId(query: String, acceptPv: Boolean = false): String? {
        return try {
            val url = "https://www.youtube.com/results?search_query=" +
                java.net.URLEncoder.encode(query, "UTF-8")
            val html = app.get(url, headers = trailerHeaders).text
            if (html.isBlank()) return null
            val idRe = Regex(""""videoId":"([A-Za-z0-9_-]{11})"""")
            val titleRe =
                Regex(""""title":\{"runs":\[\{"text":"([^"]{3,120})"""")
            var firstId: String? = null
            val seen = HashSet<String>()
            for (m in idRe.findAll(html)) {
                val id = m.groupValues[1]
                if (!seen.add(id)) continue
                if (firstId == null) firstId = id
                val end = minOf(html.length, m.range.last + 800)
                val window = html.substring(m.range.last, end)
                val t = titleRe.find(window)?.groupValues?.get(1) ?: continue
                if (t.contains("trailer", ignoreCase = true)) return id
                if (acceptPv && t.contains("PV")) return id
            }
            firstId
        } catch (_: Exception) {
            null
        }
    }

}
