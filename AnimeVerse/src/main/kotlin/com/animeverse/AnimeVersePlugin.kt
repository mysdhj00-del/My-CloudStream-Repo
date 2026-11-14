package com.animeverse

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * Subtitle mode gear (v22 — the gear was MISSING: this plugin
 * never set openSettings, so the repo list showed no gear next
 * to AnimeVerse while Goojara/ReAnime/FasselHD/Wecima2 all have
 * one). Same three options as Goojara (user spec 2026-11-12):
 * Arabic, English, or Arabic + English merged. Volatile so the
 * sites read it without a Context; persisted so it survives app
 * restarts (restored in load()). Default "dual" = the v21
 * behaviour (ready Arabic + merged dual + translated Arabic),
 * so installing v22 changes nothing until the user picks a mode.
 */
object AnimeVerseSubs {
    @Volatile
    var mode: String = "dual"

    const val PREFS = "animeverse_prefs"
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
object AnimeVerseSubsUi {
    fun show(ctx: Context, prefs: SharedPreferences) {
        runCatching {
            val current = AnimeVerseSubs.MODES.indexOf(AnimeVerseSubs.mode).takeIf { it >= 0 } ?: 2
            var selected = current
            AlertDialog.Builder(ctx)
                .setTitle("إعدادات AnimeVerse — الترجمة")
                .setMessage("اختر الترجمة التي تظهر عند تشغيل الحلقة. «العربية» تعرض العربية الجاهزة أو المترجمة فقط، «الإنجليزية» تعرض الإنجليزية فقط، «العربية + الإنجليزية» تعرضهما معًا.")
                .setSingleChoiceItems(AnimeVerseSubs.LABELS, current) { _, which ->
                    selected = which
                }
                .setPositiveButton("حفظ") { _, _ ->
                    AnimeVerseSubs.save(prefs, AnimeVerseSubs.MODES[selected])
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }
}

@CloudstreamPlugin
class AnimeVersePlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(AnimeVerseSubs.PREFS, Context.MODE_PRIVATE)
        AnimeVerseSubs.loadFrom(prefs)
        openSettings = { ctx -> AnimeVerseSubsUi.show(ctx, prefs) }
        registerMainAPI(AnimeVerseProvider())
    }
}
