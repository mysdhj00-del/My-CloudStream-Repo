package com.animeverse

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.app
import java.net.URLEncoder

/**
 * EN->AR subtitle translation for AnimeVerse (v21, ported from
 * Goojara v26): when OpenSubtitles has an English file but no
 * Arabic one, translate it on the phone — first with free Google
 * Translate (gtx, no key), and when gtx fails (Panel #10:
 * carrier-level block of googleapis on the phone's network)
 * with MyMemory (free, no key, different domain, GET
 * api.mymemory.translated.net/get). Timestamps are never
 * touched: AnimeSubs.buildSubVTT reuses the ORIGINAL start/end
 * strings and substitutes only the text lines. Translated files
 * are cached in SharedPreferences (OS URL -> stored URLs) so
 * repeat plays skip translation.
 *
 * Replaces the old gtx-only engine (AnimeSubs.translateSubCues
 * + MiruroSite's private copy): gtx alone throws on the user's
 * phone with no HTTP status at all, which is why the translate
 * fallback silently produced nothing on GogoAnime v18/v20 and
 * Aniwatch v19 (store:- / tr:fail). The new engine adds browser
 * headers + a retry for gtx, the MyMemory fallback with a daily
 * budget, a per-URL cache, and stage markers so the next failure
 * names its engine (tr:mm / tr:fail-gtx / tr:fail-mm /
 * tr:fail-mm-limit / tr:fail-store).
 */
object AnimeTranslate {

    /** Set when a gtx batch fails with HTTP 429 (rate-limited). Reset per episode. */
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

    private const val PREFS = "animeverse_subs"

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

    /**
     * Translates cue texts: gtx first, MyMemory fallback when gtx fails.
     * Null only when both engines fail. Runs on the PHONE's network.
     */
    suspend fun translateSubCues(cues: List<AnimeSubs.SubCue>): List<String>? {
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
    suspend fun translateGtx(cues: List<AnimeSubs.SubCue>): List<String>? {
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

    // ---------- MyMemory fallback (Panel #10, Goojara v26) ----------

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
    suspend fun translateMm(cues: List<AnimeSubs.SubCue>): List<String>? {
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
     * Translates parsed English cues to Arabic and stores both files:
     * «العربية» (Arabic only) and the AR+EN merged dual. The caller
     * already fetched engText and parsed the cues; this call checks
     * the cache first (repeat plays skip translation entirely),
     * translates (gtx -> MyMemory), stores via the relay, and caches
     * the stored URLs. Returns TrResult with (arUrl, dualUrl?) on
     * success — dualUrl may be null when only the merged store
     * failed — or failStage ("gtx" | "mm" | "mm-limit" | "store")
     * on failure.
     */
    suspend fun translateCues(
        cues: List<AnimeSubs.SubCue>,
        engUrl: String,
        engText: String
    ): TrResult {
        val cached = cachedAr(engUrl)
        if (cached != null) return TrResult(cached, cachedDual(engUrl), null)
        val translated = translateSubCues(cues) ?: return TrResult(
            null, null,
            when {
                mmLimit -> "mm-limit"
                mmFailed -> "mm"
                else -> "gtx"
            }
        )
        val arVtt = AnimeSubs.buildSubVTT(cues, translated)
        val arUrl = AnimeSubs.storeSub(arVtt) ?: return TrResult(null, null, "store")
        val dualUrl = AnimeSubs.storeSub(AnimeSubs.mergeSubVTT(engText, arVtt))
        putCache(engUrl, arUrl, dualUrl)
        return TrResult(arUrl, dualUrl, null)
    }
}
