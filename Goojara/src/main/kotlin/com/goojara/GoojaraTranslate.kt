package com.goojara

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.app
import java.net.URLEncoder

/**
 * EN->AR subtitle translation for Goojara (v21 gtx, v26 +MyMemory):
 * when OpenSubtitles has an English file but no Arabic one, translate
 * it on the phone — first with free Google Translate (gtx, no key),
 * and when gtx fails (Panel #10: carrier-level block of googleapis
 * on the phone's network) with MyMemory (free, no key, different
 * domain, GET api.mymemory.translated.net/get). Timestamps are never
 * touched: buildSubVTT reuses the ORIGINAL start/end strings and
 * substitutes only the text lines. Translated files are cached in
 * SharedPreferences (OS URL -> stored URLs) so repeat plays skip
 * translation.
 */
object GoojaraTranslate {
    const val relayBase = "https://anime-subs-relay.mysdhj00-del.deno.net"

    /** Set when a gtx batch fails with HTTP 429 (rate-limited). Reset per episode by the provider. */
    @Volatile
    var gtx429: Boolean = false

    /** Last gtx HTTP code on failure (0 = request threw). Reset per episode. */
    @Volatile
    var gtxCode: Int = 0

    /** Set when gtx answered 200 but the JSON did not parse as expected. */
    @Volatile
    var gtxParseFail: Boolean = false

    /** Simple name of the exception thrown by a gtx request
     * (UnknownHost, SSLHandshake, SocketTimeout, JSONException...).
     * Empty when the failure was an HTTP status instead. */
    @Volatile
    var gtxExc: String = ""

    /** Set when a translation succeeded via the MyMemory fallback. */
    @Volatile
    var mmUsed: Boolean = false

    /** Set when MyMemory failed for a non-limit reason. */
    @Volatile
    var mmFailed: Boolean = false

    /** Set when MyMemory's daily quota is exhausted. */
    @Volatile
    var mmLimit: Boolean = false

    /** Browser headers for the gtx call: Google rejects the bare
     * OkHttp user-agent with 403 on some networks. */
    private val gtxHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "https://translate.google.com"
    )

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
     * Translates cue texts: gtx first, MyMemory fallback when gtx fails.
     * Null only when both engines fail. Runs on the PHONE's network.
     */
    suspend fun translateSubCues(cues: List<SubCue>): List<String>? {
        mmUsed = false
        mmFailed = false
        mmLimit = false
        translateGtx(cues)?.let { return it }
        val mm = translateMm(cues)
        if (mm != null) mmUsed = true
        return mm
    }

    /**
     * Translates cue texts via public gtx (free, no key), char-budgeted
     * batches, paced between batches. Null on total failure.
     */
    suspend fun translateGtx(cues: List<SubCue>): List<String>? {
        gtxCode = 0
        gtxParseFail = false
        gtxExc = ""
        val out = ArrayList<String>()
        var batch = ArrayList<String>()
        var batchChars = 0
        suspend fun flush(): Boolean {
            if (batch.isEmpty()) return true
            val params = StringBuilder("client=gtx&sl=en&tl=ar&dt=t")
            for (t in batch) params.append("&q=").append(URLEncoder.encode(t, "UTF-8"))
            val texts = batch.toList()
            val ok = translateBatch(params.toString(), texts, out)
            batch = ArrayList()
            batchChars = 0
            return ok
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

    /** One gtx batch: browser headers + a single retry after 2 s. */
    private suspend fun translateBatch(
        params: String,
        texts: List<String>,
        out: ArrayList<String>
    ): Boolean {
        var attempt = 0
        while (attempt < 2) {
            attempt++
            try {
                val resp = app.get(
                    "https://translate.googleapis.com/translate_a/single?$params",
                    headers = gtxHeaders
                )
                if (!resp.isSuccessful) {
                    gtxCode = resp.code
                    if (resp.code == 429) gtx429 = true
                    if (attempt < 2) {
                        Thread.sleep(2000)
                        continue
                    }
                    return false
                }
                val arr = org.json.JSONArray(resp.text).optJSONArray(0)
                if (arr == null || arr.length() != texts.size) {
                    gtxParseFail = true
                    if (attempt < 2) {
                        Thread.sleep(2000)
                        continue
                    }
                    return false
                }
                for (i in 0 until arr.length()) {
                    val entry = arr.optJSONArray(i)
                    val tr = entry?.optString(0)?.ifEmpty { null }
                    out.add(tr ?: texts[i])
                }
                Thread.sleep(600)
                return true
            } catch (e: Exception) {
                gtxExc = e.javaClass.simpleName
                if (attempt < 2) {
                    Thread.sleep(2000)
                    continue
                }
                return false
            }
        }
        return false
    }

    // ---------- MyMemory fallback (Panel #10, v26) ----------

    /** Anonymous daily quota, chars. MyMemory's documented cap is
     * ~5,000/day; we stop at 4,500 so a file never dies mid-way. */
    private const val MM_DAILY_BUDGET = 4500

    private fun mmToday(): String {
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date())
        } catch (_: Exception) {
            ""
        }
    }

    /** Chars left of today's MyMemory budget. */
    private fun mmLeft(): Int {
        val p = prefs() ?: return MM_DAILY_BUDGET
        return try {
            val today = mmToday()
            if (p.getString("mm_day", "") != today) return MM_DAILY_BUDGET
            (MM_DAILY_BUDGET - p.getInt("mm_chars", 0)).coerceAtLeast(0)
        } catch (_: Exception) {
            MM_DAILY_BUDGET
        }
    }

    private fun mmSpend(n: Int) {
        try {
            val p = prefs() ?: return
            val today = mmToday()
            val used = if (p.getString("mm_day", "") == today) p.getInt("mm_chars", 0) else 0
            p.edit().putString("mm_day", today).putInt("mm_chars", used + n).apply()
        } catch (_: Exception) {
        }
    }

    /** One MyMemory request. Null on failure; sets mmLimit when the
     * daily quota is exhausted (429 / quotaFinished). */
    private suspend fun mmRequest(text: String): String? {
        return try {
            val url = "https://api.mymemory.translated.net/get?q=" +
                URLEncoder.encode(text, "UTF-8") + "&langpair=en|ar"
            val resp = app.get(url)
            val json = org.json.JSONObject(resp.text)
            val statusStr = json.opt("responseStatus")?.toString() ?: ""
            if (json.optBoolean("quotaFinished", false) || statusStr == "429") {
                mmLimit = true
                return null
            }
            if (statusStr != "200") return null
            val tr = json.optJSONObject("responseData")
                ?.optString("translatedText")?.ifEmpty { null } ?: return null
            mmSpend(text.length)
            tr
        } catch (_: Exception) {
            null
        }
    }

    /** Translates a small batch cue-by-cue (used when the joined
     * request fails or its line count mismatches). Over-long cues
     * keep their English text so the file stays aligned. */
    private suspend fun mmSingles(texts: List<String>, out: ArrayList<String>): Boolean {
        for (t in texts) {
            if (t.length > 450) {
                out.add(t)
                continue
            }
            if (t.length > mmLeft()) {
                mmLimit = true
                return false
            }
            val one = mmRequest(t)
            if (one == null) {
                if (!mmLimit) mmFailed = true
                return false
            }
            out.add(one)
            Thread.sleep(1000)
        }
        return true
    }

    /**
     * Translates cue texts via MyMemory (free, no key). Batches of
     * <=5 cues / <=450 chars (the per-request cap is 500, verified),
     * newlines preserved in the reply, 1 s pacing. Null on failure.
     */
    suspend fun translateMm(cues: List<SubCue>): List<String>? {
        val out = ArrayList<String>()
        var batch = ArrayList<String>()
        var batchChars = 0
        suspend fun flushBatch(): Boolean {
            if (batch.isEmpty()) return true
            val texts = batch.toList()
            batch = ArrayList()
            batchChars = 0
            val joined = texts.joinToString("\n")
            if (joined.length > mmLeft()) {
                mmLimit = true
                return false
            }
            val tr = mmRequest(joined)
            if (tr == null) {
                if (mmLimit) return false
                return mmSingles(texts, out)
            }
            val lines = tr.split("\n")
            if (lines.size != texts.size) return mmSingles(texts, out)
            out.addAll(lines)
            Thread.sleep(1000)
            return true
        }
        for (c in cues) {
            val t = c.text.replace("\n", " ")
            if (batch.isNotEmpty() && (batchChars + t.length + 1 > 450 || batch.size >= 5)) {
                if (!flushBatch()) return null
            }
            batch.add(t)
            batchChars += t.length + 1
        }
        if (!flushBatch()) return null
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
            val translated = translateSubCues(cues) ?: return TrResult(
                null, null,
                when {
                    mmLimit -> "mm-limit"
                    mmFailed -> "mm"
                    else -> "gtx"
                }
            )
            val arUrl = storeSub(buildSubVTT(cues, translated)) ?: return TrResult(null, null, "store")
            val dualUrl = storeSub(buildDualVTT(cues, translated))
            putCache(engUrl, arUrl, dualUrl)
            return TrResult(arUrl, dualUrl, null)
        }
        return TrResult(null, null, lastStage ?: "fetch")
    }
}
