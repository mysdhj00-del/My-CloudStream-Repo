package com.reanime

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * Subtitle mode gear (v4, user spec 2026-11-13): three options
 * at playback — Arabic, English, or Arabic + English merged.
 * Volatile so the provider reads it without a Context;
 * persisted so it survives app restarts (restored in load()).
 * Same pattern as Goojara's gear.
 */
object ReAnimeGear {
    @Volatile
    var mode: String = "dual"

    const val PREFS = "reanime_prefs"
    const val KEY = "sub_mode"

    val MODES = arrayOf("ar", "dual")
    val LABELS = arrayOf("العربية", "العربية + الإنجليزية")

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
object ReAnimeGearUi {
    fun show(ctx: Context, prefs: SharedPreferences) {
        runCatching {
            val current = ReAnimeGear.MODES.indexOf(ReAnimeGear.mode).takeIf { it >= 0 } ?: 1
            var selected = current
            AlertDialog.Builder(ctx)
                .setTitle("إعدادات ReAnime — الترجمة")
                .setMessage("اختر الترجمة التي تظهر عند تشغيل الحلقة. الترجمة الإنجليزية تُحوَّل إلى العربية بترجمة Google المجانية مع بقاء التوقيت.")
                .setSingleChoiceItems(ReAnimeGear.LABELS, current) { _, which ->
                    selected = which
                }
                .setPositiveButton("حفظ") { _, _ ->
                    ReAnimeGear.save(prefs, ReAnimeGear.MODES[selected])
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }
}

@CloudstreamPlugin
class ReAnimePlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(ReAnimeGear.PREFS, Context.MODE_PRIVATE)
        ReAnimeGear.loadFrom(prefs)
        openSettings = { ctx -> ReAnimeGearUi.show(ctx, prefs) }
        registerMainAPI(ReAnimeProvider())
    }
}
