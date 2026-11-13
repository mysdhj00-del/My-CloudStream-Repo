package com.reanime

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.app
import java.net.URLEncoder

/**
 * EN->AR subtitle gear for ReAnime (copied logic from AnimeVerse AnimeSubs,
 * adapted: no IMDb/OpenSubtitles lookup — the English tracks come straight
 * from the flixcloud embed page's subtitles array).
 *
 * Per English track: fetch file -> parse cues (.srt via parseSubCues,
 * .ass via parseAssCues) -> phone-side free Google Translate (gtx, no key,
 * batched + paced) -> buildSubVTT -> storeSub (relay) -> emit EN alone,
 * AR alone, AR+EN dual. Translated files are cached in SharedPreferences
 * (fileId + track file -> stored URLs) so repeat plays skip translation.
 *
 * Timestamps are never touched by translation: buildSubVTT reuses the
 * ORIGINAL start/end strings and substitutes only the text lines.
 */
object ReAnimeSubs {
    const val relayBase = "https://anime-subs-relay.mysdhj00-del.deno.net"

    /** Set when a gtx batch fails with HTTP 429 (rate-limited). Reset per episode by the provider. */
    @Volatile
    var gtx429: Boolean = false

    private const val PREFS = "reanime_subs"

    private fun prefs(): android.content.SharedPreferences? {
        return try {
            CloudStreamApp.context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        } catch (_: Exception) {
            null
        }
    }

    fun cachedAr(key: String): String? = prefs()?.getString("$key.ar", null)
    fun cachedDual(key: String): String? = prefs()?.getString("$key.dual", null)

    fun putCache(key: String, arUrl: String?, dualUrl: String?) {
        try {
            prefs()?.edit()?.apply {
                if (arUrl != null) putString("$key.ar", arUrl)
                if (dualUrl != null) putString("$key.dual", dualUrl)
            }?.apply()
        } catch (_: Exception) {
        }
    }

    // ---------- cues ----------

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

    /** SRT / WebVTT cue parser (same as AnimeSubs). */
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

    /** .ass parser: Dialogue lines only, into the same SubCue shape. */
    fun parseAssCues(text: String): List<SubCue> {
        val out = ArrayList<SubCue>()
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').lines()) {
            val line = raw.trim()
            if (!line.startsWith("Dialogue:")) continue
            // Dialogue: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text
            val body = line.removePrefix("Dialogue:").trim()
            val parts = body.split(",", limit = 10)
            if (parts.size < 10) continue
            val start = parts[1].trim()
            val end = parts[2].trim()
            val txt = cleanSubText(parts[9])
            if (txt.isEmpty()) continue
            val sMs = subTsToMs(start)
            val eMs = subTsToMs(end)
            if (eMs <= sMs) continue
            out.add(SubCue(sMs, eMs, subFmtMs(sMs), subFmtMs(eMs), txt))
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

    /** AR+EN dual VTT from the parsed cues: Arabic line above the English line, same timestamps. */
    fun buildDualVTT(cues: List<SubCue>, araTexts: List<String>): String {
        val combined = cues.mapIndexed { i, c ->
            val ara = araTexts.getOrNull(i) ?: c.text
            if (ara == c.text) c.text else "$ara\n${c.text}"
        }
        return buildSubVTT(cues, combined)
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

    /** IMDb suggest (no key): title -> tt id. */
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

    /** Best OpenSubtitles file for one language: fps 23.976 + UTF-8 first. */
    fun bestOsSub(arr: org.json.JSONArray, lang: String): String? {
        var bestUrl = ""
        var bestScore = -1
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("lang") != lang) continue
            val u = o.optString("url")
            if (u.isBlank()) continue
            var score = 0
            if (o.optInt("fpsMilli", 0) == 23976) score += 3
            val fmt = o.optString("releaseFormat")
            if (fmt == "BluRay" || fmt == "WEB") score += 2
            if (o.optString("SubEncoding") == "UTF-8") score += 1
            if (score > bestScore) {
                bestScore = score
                bestUrl = u
            }
        }
        return bestUrl.ifBlank { null }
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

    /**
     * Translates cue texts via public gtx (free, no key), char-budgeted
     * batches, paced between batches. Null on total failure. Runs on the
     * PHONE's network (its residential/mobile IP is a normal-user network);
     * never from a datacenter relay (gtx 429s/302s those).
     */
    suspend fun translateSubCues(cues: List<SubCue>): List<String>? {
        val out = ArrayList<String>()
        var batch = ArrayList<String>()
        var batchChars = 0
        suspend fun flush(): Boolean {
            if (batch.isEmpty()) return true
            return try {
                val params = StringBuilder("client=gtx&sl=en&tl=ar&dt=t")
                for (t in batch) params.append("&q=").append(URLEncoder.encode(t, "UTF-8"))
                val resp = app.get("https://translate.googleapis.com/translate_a/single?$params")
                if (!resp.isSuccessful) {
                    if (resp.code == 429) gtx429 = true
                    return false
                }
                val arr = org.json.JSONArray(resp.text).optJSONArray(0) ?: return false
                if (arr.length() != batch.size) return false
                for (i in 0 until arr.length()) {
                    val entry = arr.optJSONArray(i)
                    val tr = entry?.optString(0)?.ifEmpty { null }
                    out.add(tr ?: batch[i])
                }
                Thread.sleep(600)
                true
            } catch (e: Exception) {
                false
            } finally {
                batch = ArrayList()
                batchChars = 0
            }
        }
        for (c in cues) {
            val t = c.text.replace("\n", " ")
            if (batch.isNotEmpty() && (batchChars + t.length > 4000 || batch.size >= 50)) {
                if (!flush()) return null
            }
            batch.add(t)
            batchChars += t.length
        }
        if (!flush()) return null
        return out
    }
}
