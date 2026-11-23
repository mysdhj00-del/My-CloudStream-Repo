package com.horror

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Subtitle mode gear (v32, user order 2026-11-23 «عربية
 * إنجليزي وانجليزي» — same three modes as Goojara/AnimeVerse):
 * Arabic, English, or Arabic + English merged (default dual).
 * Volatile so the provider reads it without a Context;
 * persisted so it survives app restarts (restored in load()).
 *
 * Migration from the v6 single switch (key "auto_english"):
 * old ON -> dual (English stays on, Arabic added when the
 * source has it), old OFF -> ar (no English, as the user
 * had chosen). The old key is read once, never written.
 */
object HorrorSubs {
    @Volatile
    var mode: String = "dual"

    const val PREFS = "horror_prefs"
    const val KEY = "sub_mode"
    const val OLD_KEY = "auto_english"

    val MODES = arrayOf("ar", "en", "dual")
    val LABELS = arrayOf("العربية", "الإنجليزية", "العربية + الإنجليزية")

    fun loadFrom(prefs: SharedPreferences) {
        val saved = prefs.getString(KEY, null)
        mode = if (saved != null) {
            if (saved in MODES) saved else "dual"
        } else if (prefs.contains(OLD_KEY)) {
            if (prefs.getBoolean(OLD_KEY, true)) "dual" else "ar"
        } else {
            "dual"
        }
    }

    fun save(prefs: SharedPreferences, value: String) {
        mode = if (value in MODES) value else "dual"
        prefs.edit().putString(KEY, mode).apply()
    }
}

/** Code-built settings dialog (no resources): single-choice list. */
object HorrorSubsUi {
    fun show(ctx: Context, prefs: SharedPreferences) {
        runCatching {
            val current = HorrorSubs.MODES.indexOf(HorrorSubs.mode).takeIf { it >= 0 } ?: 2
            var selected = current
            AlertDialog.Builder(ctx)
                .setTitle("إعدادات أفلام رعب — الترجمة")
                .setMessage("اختر الترجمة التي تظهر عند تشغيل الفيلم. يبحث الترس عن ترجمة مطابقة من مصدر مجاني بدون مفتاح.")
                .setSingleChoiceItems(HorrorSubs.LABELS, current) { _, which ->
                    selected = which
                }
                .setPositiveButton("حفظ") { _, _ ->
                    HorrorSubs.save(prefs, HorrorSubs.MODES[selected])
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }
}

/** v35: persistent IMDb resolution cache (panel 2026-11-23,
 * 6 models on duck.ai, UNANIMOUS) — the v34 in-memory cache
 * died on every close/reopen, so every test was a cold
 * start of 29 titles x up to 8 searches. This cache lives
 * on disk (SharedPreferences, one JSON string), so a
 * resolved title survives app restarts: getMainPage reads
 * it and returns the row in milliseconds with zero network,
 * the background fill and the lazy tap-resolve write through
 * to it. TTL 14 days (panel majority 3/6; URLs are stable
 * for weeks). Entry: {url, poster, source, at}. */
object HorrorImdbCache {
    const val PREFS = "horror_imdb_cache"
    const val KEY = "entries"
    const val TTL_MS = 14L * 24 * 3600_000

    /** v36: seed cache — the 24/29 IMDb titles resolved
     * live from the sandbox 2026-11-23 (23 MovieBox, 1 FasselHD),
     * each with its real URL + poster. Merged into the cache
     * on first load (prefs win); the 5 missing titles are
     * filled by the background fill. `at` is the sandbox
     * resolution time, so the 14-day TTL runs from the build. */
    private const val SEED_JSON = """{"body":{"url":"https://moviebox.sg/ar/moviedetail/the-body-Qio1pYuBhK9","poster":"https://pbcdnw.aoneroom.com/image/2026/01/26/a9924e72343ef26c542b5b24056483bb.jpg","source":"moviebox","at":1795439611872},"obsession":{"url":"https://moviebox.sg/ar/moviedetail/obsession-YCQTo4djY32","poster":"https://pbcdnw.aoneroom.com/image/2026/11/22/cda12b7770c0ed7552f9d13edf30318b.jpg","source":"moviebox","at":1795439611872},"residentevil":{"url":"https://moviebox.sg/ar/moviedetail/resident-evil-Wxu1i1iEEK","poster":"https://pbcdnw.aoneroom.com/image/2025/11/01/6cd0f72f2286c84d48337e38523bb1b1.jpg","source":"moviebox","at":1795439611872},"carrie":{"url":"https://moviebox.sg/ar/moviedetail/carrie-qz9smWgu5R7","poster":"https://pbcdnw.aoneroom.com/image/2026/01/27/8a96a32228c9134cba7314b54ef8e907.jpg","source":"moviebox","at":1795439611872},"othermommy":{"url":"https://moviebox.sg/ar/moviedetail/other-mommy-84S8sHyyOJ1","poster":"https://pbcdnw.aoneroom.com/image/2026/11/17/93699deecc2eabcba6c734cfbb444c90.jpg","source":"moviebox","at":1795439611872},"backrooms":{"url":"https://moviebox.sg/ar/moviedetail/backrooms-AgThGcQKqh4","poster":"https://pbcdnw.aoneroom.com/image/2026/11/15/56a9277105fc2305e9bfb35a88699f06.jpg","source":"moviebox","at":1795439611872},"below":{"url":"https://moviebox.sg/ar/moviedetail/below-U6rHn8gQC08","poster":"https://pbcdnw.aoneroom.com/image/2026/01/27/48dd4cc659020a6867a354e36bd5967d.jpg","source":"moviebox","at":1795439611872},"insidious:outofthefurther":{"url":"https://fasselhd.com/movie:57082","poster":"http://image.tmdb.org/t/p/w500/4tTrW9dXCByS5wt2pXVWb58zNjz.jpg","source":"fassel","at":1795439611872},"hope":{"url":"https://moviebox.sg/ar/moviedetail/hope-qzPZdmrovaa","poster":"https://pbcdnw.aoneroom.com/image/2026/10/28/bed3b73afeece623905068e38b3f137f.jpg","source":"moviebox","at":1795439611872},"buddy":{"url":"https://moviebox.sg/ar/moviedetail/buddy-YKgzGGTEoZ2","poster":"https://pbcdnw.aoneroom.com/image/2026/11/20/b7ac16df889ed12d7a4b08d46b3ef7ba.jpg","source":"moviebox","at":1795439611872},"weapons":{"url":"https://moviebox.sg/ar/moviedetail/weapons-s8T1p9gGAz3","poster":"https://pbcdnw.aoneroom.com/image/2026/01/27/2c3dfde0e1baf631b3e20b3ab6fe49ed.jpg","source":"moviebox","at":1795439611872},"sinners":{"url":"https://moviebox.sg/ar/moviedetail/sinners-GVuOIeaS3G8","poster":"https://pbcdnw.aoneroom.com/image/2026/03/30/edd80948c8e469e91b8d092cc0bacd12.jpg","source":"moviebox","at":1795439611872},"418":{"url":"https://moviebox.sg/ar/moviedetail/418-gO9IeULEvv2","poster":"https://pbcdnw.aoneroom.com/image/2026/10/23/ee6ca75adc4ca0106397f2a0e031a103.jpg","source":"moviebox","at":1795439611872},"teenagesexanddeathatcampmiasma":{"url":"https://moviebox.sg/ar/moviedetail/teenage-sex-and-death-at-camp-miasma-QScu6hklis5","poster":"https://pbcdnw.aoneroom.com/image/2026/11/16/58ba925852b87e51a062974f09f9d963.jpg","source":"moviebox","at":1795439611872},"hokum":{"url":"https://moviebox.sg/ar/moviedetail/hokum-edzRC6Gmcc","poster":"https://pbcdnw.aoneroom.com/image/2026/03/28/f6b47d39018deaaf9c39d5efa3c1f59a.jpg","source":"moviebox","at":1795439611872},"victorianpsycho":{"url":"https://moviebox.sg/ar/moviedetail/victorian-psycho-mVzRa9XQYE4","poster":"https://pbcdnw.aoneroom.com/image/2026/11/14/77dcaac09607757d839d434bd1e5b3d0.jpg","source":"moviebox","at":1795439611872},"cycle":{"url":"https://moviebox.sg/ar/moviedetail/the-cycle-Wrt50WOB723","poster":"https://pbcdnw.aoneroom.com/image/2026/11/03/f90df65ad39ab78086ee1b8f7e1682a7.jpg","source":"moviebox","at":1795439611872},"lasthouse":{"url":"https://moviebox.sg/ar/moviedetail/the-last-house-OFGQnYlfCi6","poster":"https://pbcdnw.aoneroom.com/image/2026/11/13/cf4d98ce38985060fe41227da20eb506.jpg","source":"moviebox","at":1795439611872},"sendhelp":{"url":"https://moviebox.sg/ar/moviedetail/send-help-2ZQb3kKUaT2","poster":"https://pbcdnw.aoneroom.com/image/2026/11/16/ce3f2fcb025bf763b85f0051ec3d863b.jpg","source":"moviebox","at":1795439611872},"x":{"url":"https://moviebox.sg/ar/moviedetail/x-a9MSaoogMs7","poster":"https://pbcdnw.aoneroom.com/image/2023/08/10/14e0e8ef66ecd9e85ba659fe087a446d.jpg","source":"moviebox","at":1795439611872},"frankenstein":{"url":"https://moviebox.sg/ar/moviedetail/frankenstein-OxmtLAkawL5","poster":"https://pbcdnw.aoneroom.com/image/2026/11/22/659261048d706792c17fc1f931ef8aaa.jpg","source":"moviebox","at":1795439611872},"lighthouse":{"url":"https://moviebox.sg/ar/moviedetail/the-lighthouse-gcEylyxlZr3","poster":"https://pbcdnw.aoneroom.com/image/2022/10/14/5cf14db141ff68709f6e1214071be14b.jpg","source":"moviebox","at":1795439611872},"substance":{"url":"https://moviebox.sg/ar/moviedetail/the-substance-STAtC08RCW1","poster":"https://pbcdnw.aoneroom.com/image/2026/01/26/ecb623a49694d5828a0bc49f683422b9.jpg","source":"moviebox","at":1795439611872},"cujo":{"url":"https://moviebox.sg/ar/moviedetail/cujo-OfJGCxQzgK1","poster":"https://pbcdnw.aoneroom.com/image/2026/01/27/dc1b8603d05959ddedc9cadafd213b65.jpg","source":"moviebox","at":1795439611872}}"""

    data class Entry(
        val url: String,
        val poster: String?,
        val source: String,
        val at: Long
    )

    private val map = ConcurrentHashMap<String, Entry>()
    @Volatile
    private var prefs: SharedPreferences? = null

    fun loadFrom(p: SharedPreferences) {
        prefs = p
        map.clear()
        val raw = p.getString(KEY, null)
        if (raw != null) {
            runCatching {
                val j = JSONObject(raw)
                val keys = j.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val e = j.optJSONObject(k) ?: continue
                    val url = e.optString("url")
                    if (url.isBlank()) continue
                    map[k] = Entry(
                        url,
                        e.optString("poster").ifBlank { null },
                        e.optString("source").ifBlank { "?" },
                        e.optLong("at", 0L)
                    )
                }
            }
        }
        // v36: seed from the bundle (prefs win; the seed only
        // fills gaps) — the first load after install already
        // has real posters for the seeded titles.
        if (mergeSeed()) persist()
    }

    /** v36: seed entries shipped with the plugin (resolved
     * live from the sandbox 2026-11-23, 24/29 titles — the
     * other 5 are filled by the background fill, which also
     * tries the 6 HTML lanes). Returns true if it added any
     * entry. Prefs win over the seed. */
    private fun mergeSeed(): Boolean {
        var added = false
        runCatching {
            val j = JSONObject(SEED_JSON)
            val keys = j.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (map.containsKey(k)) continue
                val e = j.optJSONObject(k) ?: continue
                val url = e.optString("url")
                if (url.isBlank()) continue
                map[k] = Entry(
                    url,
                    e.optString("poster").ifBlank { null },
                    e.optString("source").ifBlank { "?" },
                    e.optLong("at", 0L)
                )
                added = true
            }
        }
        return added
    }

    fun get(key: String): Entry? = map[key]

    fun isFresh(key: String, now: Long = System.currentTimeMillis()): Boolean {
        val e = map[key] ?: return false
        return now - e.at < TTL_MS
    }

    fun put(key: String, url: String, poster: String?, source: String) {
        map[key] = Entry(url, poster, source, System.currentTimeMillis())
        persist()
    }

    private fun persist() {
        val p = prefs ?: return
        runCatching {
            val j = JSONObject()
            for ((k, e) in map) {
                val o = JSONObject()
                o.put("url", e.url)
                if (e.poster != null) o.put("poster", e.poster)
                o.put("source", e.source)
                o.put("at", e.at)
                j.put(k, o)
            }
            p.edit().putString(KEY, j.toString()).apply()
        }
    }
}

@CloudstreamPlugin
class HorrorMoviesPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(HorrorSubs.PREFS, Context.MODE_PRIVATE)
        HorrorSubs.loadFrom(prefs)
        HorrorImdbCache.loadFrom(
            context.getSharedPreferences(HorrorImdbCache.PREFS, Context.MODE_PRIVATE)
        )
        openSettings = { ctx -> HorrorSubsUi.show(ctx, prefs) }
        registerMainAPI(HorrorMoviesProvider())
    }
}
