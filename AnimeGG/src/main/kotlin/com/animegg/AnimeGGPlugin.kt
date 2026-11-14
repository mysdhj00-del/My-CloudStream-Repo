package com.animegg

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * Subtitle mode gear (v1, user spec 2026-11-14): three options
 * at playback — Arabic, English, or Arabic + English merged.
 * Volatile so the provider reads it without a Context;
 * persisted so it survives app restarts (restored in load()).
 * Same pattern as Goojara's gear.
 */
object AnimeGGGear {
    @Volatile
    var mode: String = "dual"

    const val PREFS = "animegg_prefs"
    const val KEY = "sub_mode"

    val MODES = arrayOf("ar", "en", "dual")
    val LABELS = arrayOf("العربية", "الإنجليزية", "العربية + الإنجليزية")

    fun loadFrom(prefs: SharedPreferences) {
        val saved = prefs.getString(KEY, "dual") ?: "dual"
        mode = if (saved in MODES) saved else "dual"
    }

    fun save(prefs: SharedPreferences, value: String) {
        mode = if (value in MODES) value else "dual"
        prefs.edit().putString(KEY, mode).apply()
    }
}

/** Code-built settings dialog (no resources): single-choice list. */
object AnimeGGGearUi {
    fun show(ctx: Context, prefs: SharedPreferences) {
        runCatching {
            val current = AnimeGGGear.MODES.indexOf(AnimeGGGear.mode).takeIf { it >= 0 } ?: 2
            var selected = current
            AlertDialog.Builder(ctx)
                .setTitle("إعدادات AnimeGG — الترجمة")
                .setMessage("اختر الترجمة التي تظهر عند تشغيل الحلقة. يبحث الترس عن ترجمة عربية جاهزة، وإن لم توجد يترجم الإنجليزية إلى العربية تلقائيًا بترجمة AI المجانية مع بقاء التوقيت.")
                .setSingleChoiceItems(AnimeGGGear.LABELS, current) { _, which ->
                    selected = which
                }
                .setPositiveButton("حفظ") { _, _ ->
                    AnimeGGGear.save(prefs, AnimeGGGear.MODES[selected])
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }
}

@CloudstreamPlugin
class AnimeGGPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(AnimeGGGear.PREFS, Context.MODE_PRIVATE)
        AnimeGGGear.loadFrom(prefs)
        openSettings = { ctx -> AnimeGGGearUi.show(ctx, prefs) }
        registerMainAPI(AnimeGGProvider())
    }
}
