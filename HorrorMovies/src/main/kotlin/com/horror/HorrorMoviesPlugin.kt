package com.horror

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

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

@CloudstreamPlugin
class HorrorMoviesPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(HorrorSubs.PREFS, Context.MODE_PRIVATE)
        HorrorSubs.loadFrom(prefs)
        openSettings = { ctx -> HorrorSubsUi.show(ctx, prefs) }
        registerMainAPI(HorrorMoviesProvider())
    }
}
