package com.goojara

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.app
import java.net.URLEncoder

/**
 * EN->AR subtitle translation for Goojara (v21): when OpenSubtitles has
 * an English file but no Arabic one, translate it on the phone with free
 * Google Translate (gtx, no key, batched + paced) and emit Arabic alone
 * plus an Arabic+English dual. Same engine as ReAnime's gear.
 * Timestamps are never touched: buildSubVTT reuses the ORIGINAL start/end
 * strings and substitutes only the text lines. Translated files are
 * cached in SharedPreferences (OS URL -> stored URLs) so repeat plays
 * skip translation.
 */
object GoojaraTranslate {
    const val relayBase = "https://anime-subs-relay.mysdhj00-del.deno.net"

    /** Set when a gtx batch fails with HTTP 429 (rate-limited). Reset per episode by the provider. */
    @Volatile
    var gtx429: Boolean = false

    private const val PREFS = "goojara_subs"

    private fun prefs(): android.content.SharedPreferences? {
        return try {
            CloudStreamApp.context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        } catch (_: Exception) {
            null
        }
    }

    private fun cacheKey(engUrl: String) = "os_${engUrl.hashCode()}"

    fun cachedAr(engUrl: String): String? = prefs()?.getString("${cacheKey(engUrl)}.ar", null)
    fun cachedDual(engUrl: String): String? = prefs()?.getString("${cacheKey(engUrl)}.dual", null)

    fun putCache(engUrl: String, arUrl: String?, dualUrl: String?) {
        try {
            prefs()?.edit()?.apply {
                if (arUrl != null) putString("${cacheKey(engUrl)}.ar", arUrl)
                if (dualUrl != null) putString("${cacheKey(engUrl)}.dual", dualUrl)
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

    /** Result of a translation attempt: urls on success, or the failing stage. */
    data class TrResult(val arUrl: String?, val dualUrl: String?, val failStage: String?)

    /**
     * Translates an OpenSubtitles English file to Arabic (cached).
     * Tries each candidate URL in order (best first): a dead file no
     * longer kills the whole translation. Returns TrResult with
     * (arabicUrl, dualUrl?) on success, or failStage ("fetch" | "cues" |
     * "gtx" | "store") on failure.
     */
    suspend fun translateOsEng(
        engUrls: List<String>,
        headers: Map<String, String>
    ): TrResult {
        var lastStage: String? = null
        for (engUrl in engUrls) {
            val cached = cachedAr(engUrl)
            if (cached != null) return TrResult(cached, cachedDual(engUrl), null)
            val raw = try {
                val res = app.get(engUrl, headers = headers)
                if (res.isSuccessful) res.text.takeIf { it.isNotBlank() } else null
            } catch (_: Exception) {
                null
            }
            if (raw == null) {
                lastStage = "fetch"
                continue
            }
            val cues = parseSubCues(raw)
            if (cues.isEmpty()) {
                lastStage = "cues"
                continue
            }
            val translated = translateSubCues(cues) ?: return TrResult(null, null, "gtx")
            val arUrl = storeSub(buildSubVTT(cues, translated)) ?: return TrResult(null, null, "store")
            val dualUrl = storeSub(buildDualVTT(cues, translated))
            putCache(engUrl, arUrl, dualUrl)
            return TrResult(arUrl, dualUrl, null)
        }
        return TrResult(null, null, lastStage ?: "fetch")
    }
}
