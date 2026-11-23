package com.horror

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import org.json.JSONArray
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

/** v38: persistent cache for the 6 HTML lane home rows.
 * Same pattern as HorrorImdbCache — the app awaits ALL home
 * sections before rendering, so a live lane fetch (4–5 s from
 * the sandbox, 10 s timeout on his line) held the instant IMDb
 * row hostage and the home took exactly 10 s. Now each lane
 * returns its cached cards immediately (zero network) and a
 * background refresh re-fetches the page. A stale entry still
 * shows its cards (better than empty) while the refresh runs.
 * TTL 24 h — the lane rows are "latest horror films", which
 * change daily. hasMore is stored with the entry (computed by
 * the refresh from the pagination bar, same logic as the live
 * branch) so page 1 still knows whether page 2 exists. */
object HorrorLaneCache {
    const val PREFS = "horror_lane_cache"
    const val KEY = "entries"
    const val TTL_MS = 24L * 3600_000

    /** v38: seed — the 5/6 lane home rows resolved live from
     * the sandbox 2026-11-23 (104 cards; myc is 403 from the
     * sandbox, so it has no seed — the phone's refresh fills
     * it). Merged on first load (prefs win); `at` is the
     * sandbox fetch time, so the 24 h TTL runs from the build. */
    private const val SEED_JSON = """{"moviz":{"cards":[{"t":"Housewife 2017 مترجم","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-housewife-2017-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://www.moviz.land/wp-content/uploads/2026/11/ffd756525-1-1024x576.jpg"},{"t":"Honeymoon 2014 مترجم كامل HD","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-honeymoon-2014-%d9%85%d8%aa%d8%b1%d8%ac%d9%85-%d9%83%d8%a7%d9%85%d9%84-hd/","p":"https://www.moviz.land/wp-content/uploads/2026/11/ffa4929a1-1-1024x576.jpg"},{"t":"Wired Shut 2021 مترجم","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-wired-shut-2021-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://www.moviz.land/wp-content/uploads/2026/11/ffa2297ce-1-1024x576.jpg"},{"t":"Crawlspace 2012 مترجم","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-crawlspace-2012-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://www.moviz.land/wp-content/uploads/2026/11/ff871f1ce-1-1024x576.jpg"},{"t":"The Thing 2011 مترجم","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-the-thing-2011-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://www.moviz.land/wp-content/uploads/2026/11/ff0971506-1-1024x575.jpg"},{"t":"Piranha 3D 2010 مترجم","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-piranha-3d-2010-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://www.moviz.land/wp-content/uploads/2026/11/feffcb0d3-1-1024x528.jpg"},{"t":"Brutal Realty Inc 2021 مترجم كامل HD","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-brutal-realty-inc-2021-%d9%85%d8%aa%d8%b1%d8%ac%d9%85-%d9%83%d8%a7%d9%85%d9%84-hd/","p":"https://www.moviz.land/wp-content/uploads/2026/11/fe767e5e6-1-1024x577.jpg"},{"t":"Laid to Rest 2009 مترجم","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-laid-to-rest-2009-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://www.moviz.land/wp-content/uploads/2026/11/fe6e69ade-1-1024x555.jpg"},{"t":"Magic Magic 2013 مترجم","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-magic-magic-2013-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://www.moviz.land/wp-content/uploads/2026/11/fe1934f6a-1-1024x576.jpg"},{"t":"Out Of The Shadows 2017 مترجم","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-out-of-the-shadows-2017-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://www.moviz.land/wp-content/uploads/2026/11/fdfd08397-1-1024x576.jpg"},{"t":"Buried Alive 2007 مترجم","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-buried-alive-2007-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://www.moviz.land/wp-content/uploads/2026/11/fde21d5ae-1-1024x576.jpg"},{"t":"Brain Freeze 2021 مترجم كامل HD","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-brain-freeze-2021-%d9%85%d8%aa%d8%b1%d8%ac%d9%85-%d9%83%d8%a7%d9%85%d9%84-hd/","p":"https://www.moviz.land/wp-content/uploads/2026/11/fdcbd97d8-1-1024x576.jpg"},{"t":"Infini 2015 مترجم كامل HD","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-infini-2015-%d9%85%d8%aa%d8%b1%d8%ac%d9%85-%d9%83%d8%a7%d9%85%d9%84-hd/","p":"https://www.moviz.land/wp-content/uploads/2026/11/fd1225b0f-1-1024x569.jpg"},{"t":"Haunt 2019 مترجم","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-haunt-2019-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://www.moviz.land/wp-content/uploads/2026/11/fd0857e39-1-1024x576.jpg"},{"t":"The House That Never Dies 2017 مترجم","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-the-house-that-never-dies-2017-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://www.moviz.land/wp-content/uploads/2026/11/fcd8831e3-1-1024x577.jpg"},{"t":"The Dead Dont Die 2019 مترجم","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-the-dead-dont-die-2019-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://www.moviz.land/wp-content/uploads/2026/11/fcb7f1ede-1-1024x576.jpg"},{"t":"Stay 2021 مترجم كامل HD","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-stay-2021-%d9%85%d8%aa%d8%b1%d8%ac%d9%85-%d9%83%d8%a7%d9%85%d9%84-hd/","p":"https://www.moviz.land/wp-content/uploads/2026/11/fc75fa836-1-1024x576.jpg"},{"t":"Hellraiser- Inferno 2000 مترجم","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-hellraiser-inferno-2000-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://www.moviz.land/wp-content/uploads/2026/11/fc2bf2cb4-1-1024x576.jpg"},{"t":"Nothing Left To Fear 2013 مترجم","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-nothing-left-to-fear-2013-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://www.moviz.land/wp-content/uploads/2026/11/fc2a77317-1-1024x576.jpg"},{"t":"Reva Guna Guna 2019 مترجم","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-reva-guna-guna-2019-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://www.moviz.land/wp-content/uploads/2026/11/fc1d6ca72-1-1024x576.jpg"},{"t":"Killer Sofa 2019 مترجم","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-killer-sofa-2019-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://www.moviz.land/wp-content/uploads/2026/11/fc1bb4f51-1-1024x576.jpg"},{"t":"The Samaritans 2017 مترجم","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-the-samaritans-2017-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://www.moviz.land/wp-content/uploads/2026/11/fc12259c5-1-1024x576.jpg"},{"t":"Initiation 2021 مترجم","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-initiation-2021-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://www.moviz.land/wp-content/uploads/2026/11/fbc438e5b-1-1024x576.jpg"},{"t":"Girls Against Boys 2012 مترجم","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-girls-against-boys-2012-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://www.moviz.land/wp-content/uploads/2026/11/fb5f57f7b-1-1024x576.jpg"},{"t":"Emelie 2015 مترجم","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-emelie-2015-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://www.moviz.land/wp-content/uploads/2026/11/fb133c213-1-1024x576.jpg"},{"t":"Dead Birds 2004 مترجم","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-dead-birds-2004-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://www.moviz.land/wp-content/uploads/2026/11/fae5f18e9-1-1024x574.jpg"},{"t":"Boogeyman 3 2008 مترجم","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-boogeyman-3-2008-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://www.moviz.land/wp-content/uploads/2026/11/fac41c4d4-1-1024x569.jpg"},{"t":"Spell 2020 مترجم","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-spell-2020-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://www.moviz.land/wp-content/uploads/2026/11/fab19a00c-1-1024x576.jpg"},{"t":"Dark Skies 2013 مترجم","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-dark-skies-2013-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://www.moviz.land/wp-content/uploads/2026/11/faa5595d3-1-1024x576.jpg"},{"t":"The Beach House 2019 مترجم","u":"https://www.moviz.land/%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9-%d9%81%d9%8a%d9%84%d9%85-the-beach-house-2019-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://www.moviz.land/wp-content/uploads/2026/11/fa9a6854d-1-1024x576.jpg"}],"at":1795450327965,"hasMore":true},"eg":{"cards":[{"t":"فيلم The Last Temptation Of Becky 2026 مترجم","u":"https://egy.bet/%d9%81%d9%8a%d9%84%d9%85-the-last-temptation-of-becky-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://egy.bet/wp-content/uploads/2026/11/%D9%81%D9%8A%D9%84%D9%85-The-Last-Temptation-Of-Becky-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-19972-1.jpg"},{"t":"فيلم Sanguine 2026 مترجم","u":"https://egy.bet/%d9%81%d9%8a%d9%84%d9%85-sanguine-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://egy.bet/wp-content/uploads/2026/11/%D9%81%D9%8A%D9%84%D9%85-Sanguine-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-19792-1.jpg"},{"t":"فيلم Appofeniacs 2025 مترجم","u":"https://egy.bet/%d9%81%d9%8a%d9%84%d9%85-appofeniacs-2025-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://egy.bet/wp-content/uploads/2026/11/%D9%81%D9%8A%D9%84%D9%85-Appofeniacs-2025-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-19099-1.jpg"},{"t":"فيلم Insidious Possession 2026 مترجم","u":"https://egy.bet/%d9%81%d9%8a%d9%84%d9%85-insidious-possession-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://egy.bet/wp-content/uploads/2026/11/%D9%81%D9%8A%D9%84%D9%85-Insidious-Possession-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-17907-1.jpg"},{"t":"فيلم Godzilla Minus Zero 2026 مترجم","u":"https://egy.bet/%d9%81%d9%8a%d9%84%d9%85-godzilla-minus-zero-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://egy.bet/wp-content/uploads/2026/11/%D9%81%D9%8A%D9%84%D9%85-Godzilla-Minus-Zero-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-16990-1.jpg"},{"t":"فيلم Dead Howling 2026 مترجم","u":"https://egy.bet/%d9%81%d9%8a%d9%84%d9%85-dead-howling-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://egy.bet/wp-content/uploads/2026/11/%D9%81%D9%8A%D9%84%D9%85-Dead-Howling-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-16727-1.jpg"},{"t":"فيلم Marrow 2026 مترجم","u":"https://egy.bet/%d9%81%d9%8a%d9%84%d9%85-marrow-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://egy.bet/wp-content/uploads/2026/11/%D9%81%D9%8A%D9%84%D9%85-Marrow-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-16716-1.jpg"},{"t":"فيلم The Restoration at Grayson Manor 2025 مترجم","u":"https://egy.bet/%d9%81%d9%8a%d9%84%d9%85-the-restoration-at-grayson-manor-2025-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://egy.bet/wp-content/uploads/2026/11/%D9%81%D9%8A%D9%84%D9%85-The-Restoration-at-Grayson-Manor-2025-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-16701-1.jpg"},{"t":"فيلم Vampire Carnival 2026 مترجم","u":"https://egy.bet/%d9%81%d9%8a%d9%84%d9%85-vampire-carnival-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://egy.bet/wp-content/uploads/2026/11/%D9%81%D9%8A%D9%84%D9%85-Vampire-Carnival-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-16699-1.jpg"},{"t":"فيلم X مراتي 2024","u":"https://egy.bet/%d9%81%d9%8a%d9%84%d9%85-x-%d9%85%d8%b1%d8%a7%d8%aa%d9%8a-2024/","p":"https://egy.bet/wp-content/uploads/2026/11/%D9%81%D9%8A%D9%84%D9%85-X-%D9%85%D8%B1%D8%A7%D8%AA%D9%8A-2024-16672-1.jpg"},{"t":"فيلم The Calling Witch 2026 مترجم","u":"https://egy.bet/%d9%81%d9%8a%d9%84%d9%85-the-calling-witch-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://egy.bet/wp-content/uploads/2026/11/%D9%81%D9%8A%D9%84%D9%85-The-Calling-Witch-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-16397-1.jpg"},{"t":"فيلم Return to Silent Hill 2026 مترجم","u":"https://egy.bet/%d9%81%d9%8a%d9%84%d9%85-return-to-silent-hill-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://egy.bet/wp-content/uploads/2026/11/%D9%81%D9%8A%D9%84%D9%85-Return-to-Silent-Hill-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-16388-1.jpg"},{"t":"فيلم Thrash 2026 مترجم","u":"https://egy.bet/%d9%81%d9%8a%d9%84%d9%85-thrash-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://egy.bet/wp-content/uploads/2026/11/%D9%81%D9%8A%D9%84%D9%85-Thrash-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-16381-1.jpg"},{"t":"فيلم Kraken 2026 مترجم","u":"https://egy.bet/%d9%81%d9%8a%d9%84%d9%85-kraken-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://egy.bet/wp-content/uploads/2026/11/%D9%81%D9%8A%D9%84%D9%85-Kraken-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-16368-1.jpg"},{"t":"فيلم Alpha 2026 مترجم","u":"https://egy.bet/%d9%81%d9%8a%d9%84%d9%85-alpha-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://egy.bet/wp-content/uploads/2026/11/%D9%81%D9%8A%D9%84%D9%85-Alpha-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-16360-1.jpg"},{"t":"فيلم Robotica 2026 مترجم","u":"https://egy.bet/%d9%81%d9%8a%d9%84%d9%85-robotica-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://egy.bet/wp-content/uploads/2026/11/%D9%81%D9%8A%D9%84%D9%85-Robotica-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-16297-1.jpg"},{"t":"فيلم A Ghost at Gilmore House 2027 مترجم","u":"https://egy.bet/%d9%81%d9%8a%d9%84%d9%85-a-ghost-at-gilmore-house-2027-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://egy.bet/wp-content/uploads/2026/11/%D9%81%D9%8A%D9%84%D9%85-A-Ghost-at-Gilmore-House-2027-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-16195-1.jpg"},{"t":"فيلم Dahaan 2026 مترجم","u":"https://egy.bet/%d9%81%d9%8a%d9%84%d9%85-dahaan-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://egy.bet/wp-content/uploads/2026/11/%D9%81%D9%8A%D9%84%D9%85-Dahaan-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-16175-1.jpg"},{"t":"فيلم Shark Lake 2026 مترجم","u":"https://egy.bet/%d9%81%d9%8a%d9%84%d9%85-shark-lake-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://egy.bet/wp-content/uploads/2026/11/%D9%81%D9%8A%D9%84%D9%85-Shark-Lake-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-16086-1.jpg"},{"t":"فيلم The Otherkind 2026 مترجم","u":"https://egy.bet/%d9%81%d9%8a%d9%84%d9%85-the-otherkind-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/","p":"https://egy.bet/wp-content/uploads/2026/11/%D9%81%D9%8A%D9%84%D9%85-The-Otherkind-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-16067-1.jpg"}],"at":1795450327965,"hasMore":true},"lar":{"cards":[{"t":"Written In Blood 2026 مترجم اون لاين","u":"https://larozatv.com/written-in-blood/","p":"https://larozatv.com/uploads/posters/2026/10/%D9%81%D9%8A%D9%84%D9%85-written-in-blood-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-%D8%A7%D9%88%D9%86-%D9%84%D8%A7%D9%8A%D9%86-2651.webp"},{"t":"The Cycle 2026 مترجم اون لاين","u":"https://larozatv.com/the-cycle/","p":"https://larozatv.com/uploads/posters/2026/10/%D9%81%D9%8A%D9%84%D9%85-the-cycle-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-%D8%A7%D9%88%D9%86-%D9%84%D8%A7%D9%8A%D9%86-2609.webp"},{"t":"Its Presence Between Us 2026 مترجم اون لاين","u":"https://larozatv.com/its-presence-between-us/","p":"https://larozatv.com/uploads/posters/2026/10/%D9%81%D9%8A%D9%84%D9%85-its-presence-between-us-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-%D8%A7%D9%88%D9%86-%D9%84%D8%A7%D9%8A%D9%86-2605.webp"},{"t":"The Leaching 2026 مترجم اون لاين","u":"https://larozatv.com/the-leaching/","p":"https://larozatv.com/uploads/posters/2026/10/%D9%81%D9%8A%D9%84%D9%85-the-leaching-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-%D8%A7%D9%88%D9%86-%D9%84%D8%A7%D9%8A%D9%86-2604.webp"},{"t":"Thingy 2026 مترجم اون لاين","u":"https://larozatv.com/thingy/","p":"https://larozatv.com/uploads/posters/2026/10/%D9%81%D9%8A%D9%84%D9%85-thingy-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-%D8%A7%D9%88%D9%86-%D9%84%D8%A7%D9%8A%D9%86-2603.webp"},{"t":"The Mummy 2026 مترجم اون لاين","u":"https://larozatv.com/the-mummy/","p":"https://larozatv.com/uploads/posters/2026/10/%D9%81%D9%8A%D9%84%D9%85-the-mummy-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-%D8%A7%D9%88%D9%86-%D9%84%D8%A7%D9%8A%D9%86-2581.webp"},{"t":"Grizzly Attack 2026 مترجم اون لاين","u":"https://larozatv.com/grizzly-attack/","p":"https://larozatv.com/uploads/posters/2026/10/%D9%81%D9%8A%D9%84%D9%85-grizzly-attack-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-%D8%A7%D9%88%D9%86-%D9%84%D8%A7%D9%8A%D9%86-2579.webp"},{"t":"It Wants Nightmares 2026 مترجم اون لاين","u":"https://larozatv.com/it-wants-nightmares/","p":"https://larozatv.com/uploads/posters/2026/10/%D9%81%D9%8A%D9%84%D9%85-it-wants-nightmares-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-%D8%A7%D9%88%D9%86-%D9%84%D8%A7%D9%8A%D9%86-2578.webp"},{"t":"Sleep Stalker 2025 مترجم اون لاين","u":"https://larozatv.com/sleep-stalker/","p":"https://larozatv.com/uploads/posters/2026/10/%D9%81%D9%8A%D9%84%D9%85-sleep-stalker-2025-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-%D8%A7%D9%88%D9%86-%D9%84%D8%A7%D9%8A%D9%86-2577.webp"},{"t":"The Awakening 2026 مترجم اون لاين","u":"https://larozatv.com/the-awakening/","p":"https://larozatv.com/uploads/posters/2026/10/%D9%81%D9%8A%D9%84%D9%85-the-awakening-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-%D8%A7%D9%88%D9%86-%D9%84%D8%A7%D9%8A%D9%86-2554.webp"},{"t":"DreamQuil 2026 مترجم اون لاين","u":"https://larozatv.com/dreamquil/","p":"https://larozatv.com/uploads/posters/2026/10/%D9%81%D9%8A%D9%84%D9%85-dreamquil-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-%D8%A7%D9%88%D9%86-%D9%84%D8%A7%D9%8A%D9%86-2526.webp"},{"t":"Mārama 2026 مترجم اون لاين","u":"https://larozatv.com/m-rama/","p":"https://larozatv.com/uploads/posters/2026/10/%D9%81%D9%8A%D9%84%D9%85-m%C4%81rama-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-%D8%A7%D9%88%D9%86-%D9%84%D8%A7%D9%8A%D9%86-2501.webp"},{"t":"Models Vs. Werewolves 2026 مترجم اون لاين","u":"https://larozatv.com/models-vs-werewolves/","p":"https://larozatv.com/uploads/posters/2026/10/%D9%81%D9%8A%D9%84%D9%85-models-vs-werewolves-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-%D8%A7%D9%88%D9%86-%D9%84%D8%A7%D9%8A%D9%86-2500.webp"},{"t":"أم أخرى 2026 مدبلج اون لاين","u":"https://larozatv.com/am-akhry/","p":"https://larozatv.com/uploads/posters/2026/10/%D9%81%D9%8A%D9%84%D9%85-%D8%A3%D9%85-%D8%A3%D8%AE%D8%B1%D9%89-2026-%D9%85%D8%AF%D8%A8%D9%84%D8%AC-%D8%A7%D9%88%D9%86-%D9%84%D8%A7%D9%8A%D9%86-2479.webp"},{"t":"سجين 9 2026 مدبلج اون لاين","u":"https://larozatv.com/%D9%81%D9%8A%D9%84%D9%85-%D8%B3%D8%AC%D9%8A%D9%86-9-2026-%D9%85%D8%AF%D8%A8%D9%84%D8%AC-%D8%A7%D9%88%D9%86-%D9%84%D8%A7%D9%8A%D9%86/","p":"https://larozatv.com/uploads/posters/2026/10/%D9%81%D9%8A%D9%84%D9%85-%D8%B3%D8%AC%D9%8A%D9%86-9-2026-%D9%85%D8%AF%D8%A8%D9%84%D8%AC-%D8%A7%D9%88%D9%86-%D9%84%D8%A7%D9%8A%D9%86-2312.webp"},{"t":"سجين 9 Siccîn 9 2026 مترجم اون لاين","u":"https://larozatv.com/%D9%81%D9%8A%D9%84%D9%85-%D8%B3%D8%AC%D9%8A%D9%86-9-sicc%C3%AEn-9-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-%D8%A7%D9%88%D9%86-%D9%84%D8%A7%D9%8A%D9%86/","p":"https://larozatv.com/uploads/posters/2026/10/%D9%81%D9%8A%D9%84%D9%85-%D8%B3%D8%AC%D9%8A%D9%86-9-sicc%C3%AEn-9-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85-%D8%A7%D9%88%D9%86-%D9%84%D8%A7%D9%8A%D9%86-2311.webp"}],"at":1795450327965,"hasMore":true},"top":{"cards":[{"t":"Insidious: Out of the Further 2026 مترجم اون لاين ★ 6.2","u":"https://topcinemaa.one/%d9%81%d9%8a%d9%84%d9%85-insidious-out-of-the-further-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85-%d8%a7%d9%88%d9%86-%d9%84%d8%a7%d9%8a%d9%86/","p":"https://topcinemaa.one/wp-content/uploads/2026/09/MV5BMTY4YjM1OGItMDlmMi00OTU1LWEzM2MtZGMyZTQ1NzhiM2VhXkEyXkFqcGc@-367x550.jpg"},{"t":"Victorian Psycho 2026 مترجم اون لاين ★ 6.3","u":"https://topcinemaa.one/%d9%81%d9%8a%d9%84%d9%85-victorian-psycho-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85-%d8%a7%d9%88%d9%86-%d9%84%d8%a7%d9%8a%d9%86/","p":"https://topcinemaa.one/wp-content/uploads/2026/11/MV5BNjdmNmYwMzEtMzJmYS00MDkxLThiNGItZjA0MGJlMjU4MTNmXkEyXkFqcGc@-366x550.jpg"},{"t":"Never after Dark 2026 مترجم اون لاين ★ 6.5","u":"https://topcinemaa.one/%d9%81%d9%8a%d9%84%d9%85-never-after-dark-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85-%d8%a7%d9%88%d9%86-%d9%84%d8%a7%d9%8a%d9%86/","p":"https://topcinemaa.one/wp-content/uploads/2026/11/ckvx8NASv9ZCeGqpMkCMUF3HRGI-370x550.jpg"},{"t":"The Last Temptation of Becky 2026 مترجم اون لاين ★ 6.3","u":"https://topcinemaa.one/%d9%81%d9%8a%d9%84%d9%85-the-last-temptation-of-becky-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85-%d8%a7%d9%88%d9%86-%d9%84%d8%a7%d9%8a%d9%86/","p":"https://topcinemaa.one/wp-content/uploads/2026/11/_640x_65854192aec21c5950df612635fee37347ba955e34975f8762499fe020f755dc-381x550.jpg"},{"t":"Sanguine 2026 مترجم اون لاين ★ 6.6","u":"https://topcinemaa.one/%d9%81%d9%8a%d9%84%d9%85-sanguine-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85-%d8%a7%d9%88%d9%86-%d9%84%d8%a7%d9%8a%d9%86/","p":"https://topcinemaa.one/wp-content/uploads/2026/11/MV5BODNmMDQwYTUtMDhjMC00NzAwLWE2ZjYtM2M2YTBjOTNmNmY2XkEyXkFqcGc@-404x550.jpg"},{"t":"Appofeniacs 2026 مترجم اون لاين ★ 6.2","u":"https://topcinemaa.one/%d9%81%d9%8a%d9%84%d9%85-appofeniacs-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85-%d8%a7%d9%88%d9%86-%d9%84%d8%a7%d9%8a%d9%86/","p":"https://topcinemaa.one/wp-content/uploads/2026/11/MV5BMmI3MDZjOGMtZTc3My00NGYyLWE2OTEtYjBiZTllZDY3OTU2XkEyXkFqcGc@-371x550.jpg"},{"t":"Insidious Possession 2026 مترجم اون لاين ★ 4.5","u":"https://topcinemaa.one/%d9%81%d9%8a%d9%84%d9%85-insidious-possession-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85-%d8%a7%d9%88%d9%86-%d9%84%d8%a7%d9%8a%d9%86/","p":"https://topcinemaa.one/wp-content/uploads/2026/11/MV5BNzdhMTg4YWEtMWRkZC00ZjVkLThmNDctOTZkOTlkZTcxOGQ5XkEyXkFqcGc@-scaled-367x550.jpg"},{"t":"Godzilla Minus Zero 2026 مترجم اون لاين ★ 8","u":"https://topcinemaa.one/%d9%81%d9%8a%d9%84%d9%85-godzilla-minus-zero-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85-%d8%a7%d9%88%d9%86-%d9%84%d8%a7%d9%8a%d9%86/","p":"https://topcinemaa.one/wp-content/uploads/2026/11/_640x_1faa625a00e6ac6b670e860dde53d261156d366607aee9374bc7205bdc1691ce-376x550.jpg"},{"t":"Gaskins: The Hitchhiker Killer 2026 مترجم اون لاين ★ 3.4","u":"https://topcinemaa.one/%d9%81%d9%8a%d9%84%d9%85-gaskins-the-hitchhiker-killer-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85-%d8%a7%d9%88%d9%86-%d9%84%d8%a7%d9%8a%d9%86/","p":"https://topcinemaa.one/wp-content/uploads/2026/11/MV5BZDFmZDIxNDMtNzJlMy00ZGU2LWIxMTEtY2MyODQ4NTVlODJlXkEyXkFqcGc@-367x550.jpg"},{"t":"Other Mommy 2026 مترجم اون لاين ★ 5.6","u":"https://topcinemaa.one/%d9%81%d9%8a%d9%84%d9%85-other-mommy-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85-%d8%a7%d9%88%d9%86-%d9%84%d8%a7%d9%8a%d9%86/","p":"https://topcinemaa.one/wp-content/uploads/2026/10/MV5BOGUyODYyYTctMmY0Mi00ZWIwLThjYWItZDJmODY4NGU1YjY1XkEyXkFqcGc@-scaled-347x550.jpg"},{"t":"Shark Lake 2026 مترجم اون لاين ★ 5.2","u":"https://topcinemaa.one/%d9%81%d9%8a%d9%84%d9%85-shark-lake-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85-%d8%a7%d9%88%d9%86-%d9%84%d8%a7%d9%8a%d9%86/","p":"https://topcinemaa.one/wp-content/uploads/2026/11/MV5BZTVlNTM3ZDItZGY5My00NmI4LWJiY2YtNTg5MzdhNGVlYWZhXkEyXkFqcGc@._V1_-scaled-367x550.jpg"},{"t":"A Ghost at Gilmore House 2027 مترجم اون لاين","u":"https://topcinemaa.one/%d9%81%d9%8a%d9%84%d9%85-a-ghost-at-gilmore-house-2027-%d9%85%d8%aa%d8%b1%d8%ac%d9%85-%d8%a7%d9%88%d9%86-%d9%84%d8%a7%d9%8a%d9%86/","p":"https://topcinemaa.one/wp-content/uploads/2026/11/MV5BMDU2YjI4ZmYtMGQwZS00NmMyLWE3NjAtYTYyNDY4ODkyMDVhXkEyXkFqcGc@-scaled-371x550.jpg"},{"t":"The Living Tree 2026 مترجم اون لاين ★ 6.3","u":"https://topcinemaa.one/%d9%81%d9%8a%d9%84%d9%85-the-living-tree-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85-%d8%a7%d9%88%d9%86-%d9%84%d8%a7%d9%8a%d9%86/","p":"https://topcinemaa.one/wp-content/uploads/2026/11/MV5BZjc0ZGRlZjAtZmJmNS00NzIzLWE4NjItMWU4OTc1YWVjNjlhXkEyXkFqcGc@-367x550.jpg"},{"t":"Beware Boiúna 2026 مترجم اون لاين ★ 7","u":"https://topcinemaa.one/%d9%81%d9%8a%d9%84%d9%85-beware-boiuna-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85-%d8%a7%d9%88%d9%86-%d9%84%d8%a7%d9%8a%d9%86/","p":"https://topcinemaa.one/wp-content/uploads/2026/10/MV5BMDQxZDM1NGYtOTlmMS00ZDUxLWJlNDEtZjRiMGIyNzZmM2U0XkEyXkFqcGc@-371x550.jpg"},{"t":"The Canvas 2026 مترجم اون لاين ★ 5.2","u":"https://topcinemaa.one/%d9%81%d9%8a%d9%84%d9%85-the-canvas-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85-%d8%a7%d9%88%d9%86-%d9%84%d8%a7%d9%8a%d9%86/","p":"https://topcinemaa.one/wp-content/uploads/2026/11/MV5BYzQ0MTI0MmEtZTVmOS00NzBmLTlkNjEtNWRmOGQzZjE2ZGVhXkEyXkFqcGc@-371x550.jpg"},{"t":"Resident Evil 2026 مترجم اون لاين ★ 7.7","u":"https://topcinemaa.one/%d9%81%d9%8a%d9%84%d9%85-resident-evil-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85-%d8%a7%d9%88%d9%86-%d9%84%d8%a7%d9%8a%d9%86/","p":"https://topcinemaa.one/wp-content/uploads/2026/09/MV5BZGVmNzRlZTctNTU2OS00MTQwLWI2ZWQtOGViMjc4ODNlNzVmXkEyXkFqcGc@-440x550.jpg"},{"t":"A Story of Derry 2026 مترجم اون لاين","u":"https://topcinemaa.one/%d9%81%d9%8a%d9%84%d9%85-a-story-of-derry-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85-%d8%a7%d9%88%d9%86-%d9%84%d8%a7%d9%8a%d9%86/","p":"https://topcinemaa.one/wp-content/uploads/2026/11/q2XCoT6AqBANz0mKmkgVeo4PyRQ-367x550.jpg"},{"t":"Apocalypse Z: New World 2026 مترجم اون لاين ★ 6.8","u":"https://topcinemaa.one/%d9%81%d9%8a%d9%84%d9%85-apocalypse-z-new-world-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85-%d8%a7%d9%88%d9%86-%d9%84%d8%a7%d9%8a%d9%86/","p":"https://topcinemaa.one/wp-content/uploads/2026/10/MV5BYTU2OTU5YjgtYTNmMS00OGI2LWE5ZWQtODYyYTQ0ZDk0NzJmXkEyXkFqcGc@._V1_-372x550.jpg"}],"at":1795450327965,"hasMore":true},"af":{"cards":[{"t":"Crawlers 2026 مترجم","u":"https://aflamstream.com/%d9%81%d9%8a%d9%84%d9%85-crawlers-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/14314/","p":"https://aflamstream.com/wp-content/uploads/2026/11/%D9%81%D9%8A%D9%84%D9%85-Crawlers-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85.jpg"},{"t":"Beware Boiuna 2026 مترجم","u":"https://aflamstream.com/%d9%81%d9%8a%d9%84%d9%85-beware-boiuna-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/14310/","p":"https://aflamstream.com/wp-content/uploads/2026/11/%D9%81%D9%8A%D9%84%D9%85-Beware-Boiuna-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85.webp"},{"t":"The Canvas 2026 مترجم","u":"https://aflamstream.com/%d9%81%d9%8a%d9%84%d9%85-the-canvas-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/14303/","p":"https://aflamstream.com/wp-content/uploads/2026/11/%D9%81%D9%8A%D9%84%D9%85-The-Canvas-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85.jpg"},{"t":"Apocalypse Z New World 2026 مترجم","u":"https://aflamstream.com/%d9%81%d9%8a%d9%84%d9%85-apocalypse-z-new-world-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/14219/","p":"https://aflamstream.com/wp-content/uploads/2026/11/%D9%81%D9%8A%D9%84%D9%85-Apocalypse-Z-New-World-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85.jpg"},{"t":"Its Presence Between Us 2026 مترجم","u":"https://aflamstream.com/%d9%81%d9%8a%d9%84%d9%85-its-presence-between-us-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/13938/","p":"https://aflamstream.com/wp-content/uploads/2026/10/%D9%81%D9%8A%D9%84%D9%85-Its-Presence-Between-Us-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85.jpg"},{"t":"Speed Demon 2026 مترجم","u":"https://aflamstream.com/%d9%81%d9%8a%d9%84%d9%85-speed-demon-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/13935/","p":"https://aflamstream.com/wp-content/uploads/2026/10/%D9%81%D9%8A%D9%84%D9%85-Speed-Demon-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85.webp"},{"t":"The Conjuring 1 2013 مترجم","u":"https://aflamstream.com/%d9%81%d9%8a%d9%84%d9%85-the-conjuring-2013-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/10160/","p":"https://aflamstream.com/wp-content/uploads/2026/03/The-Conjuring-2013-%D9%85%D8%AA%D8%B1%D8%AC%D9%85.png"},{"t":"The Horde 2009 مترجم","u":"https://aflamstream.com/%d9%81%d9%8a%d9%84%d9%85-the-horde-2009-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/13650/","p":"https://aflamstream.com/wp-content/uploads/2026/10/%D9%81%D9%8A%D9%84%D9%85-The-Horde-2009-%D9%85%D8%AA%D8%B1%D8%AC%D9%85.png"},{"t":"Hope 2026 مترجم","u":"https://aflamstream.com/%d9%81%d9%8a%d9%84%d9%85-hope-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/12852/","p":"https://aflamstream.com/wp-content/uploads/2026/09/%D9%81%D9%8A%D9%84%D9%85-Hope-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85.webp"},{"t":"Siccin 9 2026 مترجم","u":"https://aflamstream.com/%d9%81%d9%8a%d9%84%d9%85-siccin-9-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/13449/","p":"https://aflamstream.com/wp-content/uploads/2026/10/%D9%81%D9%8A%D9%84%D9%85-Siccin-9-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85.png"},{"t":"Insidious Out Further 2026 مترجم","u":"https://aflamstream.com/%d9%81%d9%8a%d9%84%d9%85-insidious-out-further-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/13063/","p":"https://aflamstream.com/wp-content/uploads/2026/10/%D9%81%D9%8A%D9%84%D9%85-Insidious-Out-Further-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85.png"},{"t":"Smile 2 2024 مترجم","u":"https://aflamstream.com/%d9%81%d9%8a%d9%84%d9%85-smile-2-2024-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/12976/","p":"https://aflamstream.com/wp-content/uploads/2026/10/%D9%81%D9%8A%D9%84%D9%85-Smile-2-2024-%D9%85%D8%AA%D8%B1%D8%AC%D9%85.webp"},{"t":"Resident Evil 2026 مترجم","u":"https://aflamstream.com/%d9%81%d9%8a%d9%84%d9%85-resident-evil-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/12926/","p":"https://aflamstream.com/wp-content/uploads/2026/10/%D9%81%D9%8A%D9%84%D9%85-Resident-Evil-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85.jpg"},{"t":"The Menu 2022 مترجم","u":"https://aflamstream.com/%d9%81%d9%8a%d9%84%d9%85-the-menu-2022-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/12920/","p":"https://aflamstream.com/wp-content/uploads/2026/10/%D9%81%D9%8A%D9%84%D9%85-The-Menu-2022-%D9%85%D8%AA%D8%B1%D8%AC%D9%85.webp"},{"t":"Teenage Sex and Death at Camp Miasma 2026 مترجم","u":"https://aflamstream.com/%d9%81%d9%8a%d9%84%d9%85-teenage-sex-and-death-at-camp-miasma-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/12915/","p":"https://aflamstream.com/wp-content/uploads/2026/10/%D9%81%D9%8A%D9%84%D9%85-Teenage-Sex-and-Death-at-Camp-Miasma-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85.webp"},{"t":"Salmokji Whispering Water 2026 مترجم","u":"https://aflamstream.com/%d9%81%d9%8a%d9%84%d9%85-salmokji-whispering-water-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/12890/","p":"https://aflamstream.com/wp-content/uploads/2026/09/%D9%81%D9%8A%D9%84%D9%85-Salmokji-Whispering-Water-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85.jpg"},{"t":"Escape Room 2019 مترجم","u":"https://aflamstream.com/%d9%81%d9%8a%d9%84%d9%85-escape-room-2019-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/12849/","p":"https://aflamstream.com/wp-content/uploads/2026/09/%D9%81%D9%8A%D9%84%D9%85-Escape-Room-2019-%D9%85%D8%AA%D8%B1%D8%AC%D9%85.webp"},{"t":"Bury the Devil 2026 مترجم","u":"https://aflamstream.com/%d9%81%d9%8a%d9%84%d9%85-bury-the-devil-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/12834/","p":"https://aflamstream.com/wp-content/uploads/2026/09/%D9%81%D9%8A%D9%84%D9%85-Bury-the-Devil-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85.png"},{"t":"The Lake 2022 مترجم","u":"https://aflamstream.com/%d9%81%d9%8a%d9%84%d9%85-the-lake-2022-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/12807/","p":"https://aflamstream.com/wp-content/uploads/2026/09/%D9%81%D9%8A%D9%84%D9%85-The-Lake-2022-%D9%85%D8%AA%D8%B1%D8%AC%D9%85.webp"},{"t":"Evil Dead Burn 2026 مترجم","u":"https://aflamstream.com/%d9%81%d9%8a%d9%84%d9%85-evil-dead-burn-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/12637/","p":"https://aflamstream.com/wp-content/uploads/2026/09/%D9%81%D9%8A%D9%84%D9%85-Evil-Dead-Burn-2026-%D9%85%D8%AA%D8%B1%D8%AC%D9%85.jpg"}],"at":1795450327965,"hasMore":true}}"""

    data class Card(val title: String, val url: String, val poster: String?)
    data class Entry(val cards: List<Card>, val at: Long, val hasMore: Boolean)

    private val map = ConcurrentHashMap<String, Entry>()
    @Volatile
    private var prefs: SharedPreferences? = null

    /** Parses one lane entry ({cards:[{t,u,p}], at, hasMore});
     * null when it carries no usable card. */
    private fun parseEntry(e: JSONObject): Entry? {
        val arr = e.optJSONArray("cards") ?: return null
        val cards = ArrayList<Card>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val u = c.optString("u")
            if (u.isBlank()) continue
            cards += Card(c.optString("t"), u, c.optString("p").ifBlank { null })
        }
        if (cards.isEmpty()) return null
        return Entry(cards, e.optLong("at", 0L), e.optBoolean("hasMore", true))
    }

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
                    parseEntry(e)?.let { map[k] = it }
                }
            }
        }
        // v38: seed from the bundle (prefs win; the seed only
        // fills gaps) — the first load after install already
        // has real cards for the seeded lanes.
        if (mergeSeed()) persist()
    }

    /** v38: seed entries shipped with the plugin (see SEED_JSON
     * above). Returns true if it added any entry. Prefs win
     * over the seed. */
    private fun mergeSeed(): Boolean {
        var added = false
        runCatching {
            val j = JSONObject(SEED_JSON)
            val keys = j.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (map.containsKey(k)) continue
                val e = j.optJSONObject(k) ?: continue
                parseEntry(e)?.let {
                    map[k] = it
                    added = true
                }
            }
        }
        return added
    }

    fun get(lane: String): Entry? = map[lane]

    fun put(lane: String, cards: List<Card>, hasMore: Boolean) {
        map[lane] = Entry(cards, System.currentTimeMillis(), hasMore)
        persist()
    }

    private fun persist() {
        val p = prefs ?: return
        runCatching {
            val j = JSONObject()
            for ((k, e) in map) {
                val o = JSONObject()
                val arr = JSONArray()
                for (c in e.cards) {
                    val co = JSONObject()
                    co.put("t", c.title)
                    co.put("u", c.url)
                    if (c.poster != null) co.put("p", c.poster)
                    arr.put(co)
                }
                o.put("cards", arr)
                o.put("at", e.at)
                o.put("hasMore", e.hasMore)
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
        HorrorLaneCache.loadFrom(
            context.getSharedPreferences(HorrorLaneCache.PREFS, Context.MODE_PRIVATE)
        )
        openSettings = { ctx -> HorrorSubsUi.show(ctx, prefs) }
        registerMainAPI(HorrorMoviesProvider())
    }
}
