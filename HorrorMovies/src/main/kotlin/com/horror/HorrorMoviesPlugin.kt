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
        val raw = p.getString(KEY, null) ?: return
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
