package com.merged

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * Subtitle gear for the Merged extension (v1): two switches cover
 * all four modes — English ON + Arabic ON = both together;
 * one ON = that language only; both OFF = no subtitles.
 * Volatile so the provider reads it without a Context;
 * persisted so it survives app restarts (restored in load()).
 */
object MergedSubs {
    @Volatile
    var english: Boolean = true

    @Volatile
    var arabic: Boolean = true

    const val PREFS = "merged_prefs"
    const val KEY_EN = "sub_english"
    const val KEY_AR = "sub_arabic"

    fun loadFrom(prefs: SharedPreferences) {
        english = prefs.getBoolean(KEY_EN, true)
        arabic = prefs.getBoolean(KEY_AR, true)
    }

    fun save(prefs: SharedPreferences, en: Boolean, ar: Boolean) {
        english = en
        arabic = ar
        prefs.edit().putBoolean(KEY_EN, en).putBoolean(KEY_AR, ar).apply()
    }
}

/** Code-built settings dialog (no resources): two switches. */
object MergedSubsUi {
    fun show(ctx: Context, prefs: SharedPreferences) {
        runCatching {
            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 32, 48, 8)
            }
            val hint = TextView(ctx).apply {
                text = "الترجمة الأوتوماتيكية للأفلام والأنمي.\n" +
                    "تشغيل الاثنين معًا يعرض الإنجليزية والعربية " +
                    "في نفس الوقت (ملف ترجمة مدمج، والفيديو لا يُمس)."
            }
            val enToggle = Switch(ctx).apply {
                text = "الترجمة الإنجليزية"
                isChecked = MergedSubs.english
            }
            val arToggle = Switch(ctx).apply {
                text = "الترجمة العربية"
                isChecked = MergedSubs.arabic
            }
            layout.addView(hint)
            layout.addView(enToggle)
            layout.addView(arToggle)
            AlertDialog.Builder(ctx)
                .setTitle("إعدادات الترجمة — مدمج")
                .setView(layout)
                .setPositiveButton("حفظ") { _, _ ->
                    MergedSubs.save(prefs, enToggle.isChecked, arToggle.isChecked)
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }
}

@CloudstreamPlugin
class MergedPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(MergedSubs.PREFS, Context.MODE_PRIVATE)
        MergedSubs.loadFrom(prefs)
        openSettings = { ctx -> MergedSubsUi.show(ctx, prefs) }
        registerMainAPI(MergedProvider())
    }
}
