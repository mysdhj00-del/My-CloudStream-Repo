package com.movieboxin

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.app
import java.net.URLEncoder

/**
 * EN->AR cue-text translation for MovieBoxIN (v16, ported from the
 * AnimeTranslate.kt / GoojaraTranslate.kt pattern in this repo):
 * when the dual «الإنجليزية + العربية» cannot be built from the two
 * existing tracks (the Arabic URL is dead or unmergeable — the
 * [ترجمة عربية] version's ids carry no sidecar subs on any route,
 * v4), translate the English track's cues to Arabic on the phone —
 * first with free Google Translate (gtx, no key), and when gtx
 * fails with MyMemory (free, no key, different domain, GET
 * api.mymemory.translated.net/get). Timestamps are never touched:
 * the caller keeps the ORIGINAL English cues' start/end and
 * substitutes only the text lines, so cue order and timing are
 * preserved exactly. Translated files are cached in
 * SharedPreferences (English URL -> stored URLs) so repeat plays
 * skip translation.
 *
 * Added by Mohamed's explicit order (2026-11-16 22:36: «اعملها
 * حتى بai اجعل الترجمة انجليزية تتحول لعربي واجعلهم يظخرو مع بعض
 * مثال لو لم تستطيع») — this LIFTS his original 01:11
 * «no machine translation» rule for the DUAL FALLBACK ONLY.
 * No OpenSubtitles (the fallback translates the existing English
 * track, it never fetches subtitles from OpenSubtitles) and no
 * paid services (both engines are free, no key).
 *
 * Live facts (trace40_v16.py, 2026-11-16): gtx from the sandbox
 * answers 200 for the first batch, then 429 persistently (even
 * after a 120 s pause) — and the first batch returned 1
 * translation for 10 cues, so the length check below (a mismatch
 * is a parse failure) is load-bearing. MyMemory answers 200 from
 * the sandbox with newlines preserved. On the phone gtx may be
 * carrier-blocked (Panel #10, Goojara lesson) — MyMemory is the
 * fallback, but its anonymous budget (~4,500 chars/day) cannot
 * cover a full ~470-cue episode (~16,000 chars) in one day, so
 * the fallback may end in ai-mm-limit and the DEBUG line names
 * it instead of failing silently.
 */
object MbTranslate {

    /** Set when a gtx batch fails with HTTP 429 (rate-limited). Reset per episode. */
    @Volatile
    var gtx429: Boolean = false

    /** Last gtx HTTP code on failure (0 = request threw). Reset per episode. */
    @Volatile
    var gtxCode: Int = 0

    /** Set when gtx answered 200 but the JSON did not parse as expected
     * (including a length mismatch — fewer translations than cues). */
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

    private const val PREFS = "movieboxin_subs"

    private fun prefs(): android.content.SharedPreferences? {
        return try {
            CloudStreamApp.context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        } catch (_: Exception) {
            null
        }
    }

    private fun cacheKey(engUrl: String) = "ai_${engUrl.hashCode()}"

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

    /** Result of a translation attempt: translated texts + the engine
     * that produced them ("gtx" | "mm"), or null + the failing stage
     * ("ai-gtx" | "ai-mm" | "ai-mm-limit" | "ai-timeout"). */
    data class MbTrResult(
        val texts: List<String>?,
        val engine: String?,
        val failStage: String?
    )

    /**
     * Translates cue texts: gtx first, MyMemory fallback when gtx fails.
     * deadlineMs caps the whole attempt (the caller passes start + 45 s)
     * so a slow translation never stalls playback start. Null texts
     * only when both engines fail or the deadline hits.
     */
    suspend fun mbTranslateCueTexts(
        texts: List<String>,
        deadlineMs: Long
    ): MbTrResult {
        mmUsed = false
        mmFailed = false
        mmLimit = false
        translateGtx(texts, deadlineMs)?.let { return MbTrResult(it, "gtx", null) }
        if (System.currentTimeMillis() > deadlineMs) {
            return MbTrResult(null, null, "ai-timeout")
        }
        val mm = translateMm(texts, deadlineMs)
        if (mm != null) {
            mmUsed = true
            return MbTrResult(mm, "mm", null)
        }
        val stage = when {
            System.currentTimeMillis() > deadlineMs -> "ai-timeout"
            mmLimit -> "ai-mm-limit"
            mmFailed -> "ai-mm"
            else -> "ai-gtx"
        }
        return MbTrResult(null, null, stage)
    }

    /**
     * Translates cue texts via public gtx (free, no key), char-budgeted
     * batches, paced between batches. Null on total failure.
     */
    private suspend fun translateGtx(
        texts: List<String>,
        deadlineMs: Long
    ): List<String>? {
        gtxCode = 0
        gtxParseFail = false
        gtxExc = ""
        val out = ArrayList<String>()
        var batch = ArrayList<String>()
        var batchChars = 0
        suspend fun flush(): Boolean {
            if (batch.isEmpty()) return true
            if (System.currentTimeMillis() > deadlineMs) return false
            val params = StringBuilder("client=gtx&sl=en&tl=ar&dt=t")
            for (t in batch) params.append("&q=").append(URLEncoder.encode(t, "UTF-8"))
            val batchTexts = batch.toList()
            val ok = translateBatch(params.toString(), batchTexts, out)
            batch = ArrayList()
            batchChars = 0
            return ok
        }
        for (t in texts) {
            val one = t.replace("\n", " ")
            if (batch.isNotEmpty() && (batchChars + one.length > 4000 || batch.size >= 50)) {
                if (!flush()) return null
            }
            batch.add(one)
            batchChars += one.length
        }
        if (!flush()) return null
        return out
    }

    /** One gtx batch: browser headers + a single retry after 2 s.
     * A length mismatch (fewer translations than cues — seen live
     * T127: 1 translation for 10 cues) is a parse failure, retried
     * once, then the whole gtx attempt fails over to MyMemory. */
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
     * ~5,000/day; we stop at 4,500 so a file never dies mid-way.
     * A full ~470-cue episode is ~16,000 chars, so the anonymous
     * budget alone cannot cover it — it is a fallback for when
     * gtx is blocked, not a full-episode engine. */
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
    private suspend fun mmSingles(
        texts: List<String>,
        out: ArrayList<String>,
        deadlineMs: Long
    ): Boolean {
        for (t in texts) {
            if (System.currentTimeMillis() > deadlineMs) return false
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
    private suspend fun translateMm(
        texts: List<String>,
        deadlineMs: Long
    ): List<String>? {
        val out = ArrayList<String>()
        var batch = ArrayList<String>()
        var batchChars = 0
        suspend fun flushBatch(): Boolean {
            if (batch.isEmpty()) return true
            if (System.currentTimeMillis() > deadlineMs) return false
            val batchTexts = batch.toList()
            batch = ArrayList()
            batchChars = 0
            val joined = batchTexts.joinToString("\n")
            if (joined.length > mmLeft()) {
                mmLimit = true
                return false
            }
            val tr = mmRequest(joined)
            if (tr == null) {
                if (mmLimit) return false
                return mmSingles(batchTexts, out, deadlineMs)
            }
            val lines = tr.split("\n")
            if (lines.size != batchTexts.size) return mmSingles(batchTexts, out, deadlineMs)
            out.addAll(lines)
            Thread.sleep(1000)
            return true
        }
        for (t in texts) {
            val one = t.replace("\n", " ")
            if (batch.isNotEmpty() && (batchChars + one.length + 1 > 450 || batch.size >= 5)) {
                if (!flushBatch()) return null
            }
            batch.add(one)
            batchChars += one.length + 1
        }
        if (!flushBatch()) return null
        return out
    }
}
