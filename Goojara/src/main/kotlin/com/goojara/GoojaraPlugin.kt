package com.goojara

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * Subtitle mode gear (v1, user spec 2026-11-12): three options
 * at playback — Arabic, English, or Arabic + English merged.
 * Volatile so the provider reads it without a Context;
 * persisted so it survives app restarts (restored in load()).
 */
object GoojaraSubs {
    @Volatile
    var mode: String = "dual"

    const val PREFS = "goojara_prefs"
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
object GoojaraSubsUi {
    fun show(ctx: Context, prefs: SharedPreferences) {
        runCatching {
            val current = GoojaraSubs.MODES.indexOf(GoojaraSubs.mode).takeIf { it >= 0 } ?: 2
            var selected = current
            AlertDialog.Builder(ctx)
                .setTitle("إعدادات Goojara — الترجمة")
                .setMessage("اختر الترجمة التي تظهر عند تشغيل الفيلم أو الحلقة. يبحث الترس عن ترجمة مطابقة من مصدر مجاني بدون مفتاح.")
                .setSingleChoiceItems(GoojaraSubs.LABELS, current) { _, which ->
                    selected = which
                }
                .setPositiveButton("حفظ") { _, _ ->
                    GoojaraSubs.save(prefs, GoojaraSubs.MODES[selected])
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }
}

@CloudstreamPlugin
class GoojaraPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(GoojaraSubs.PREFS, Context.MODE_PRIVATE)
        GoojaraSubs.loadFrom(prefs)
        openSettings = { ctx -> GoojaraSubsUi.show(ctx, prefs) }
        registerMainAPI(GoojaraProvider())
    }
}
