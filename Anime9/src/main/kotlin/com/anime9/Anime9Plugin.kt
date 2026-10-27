package com.anime9

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * Subtitle gear for the Anime9 extension: two switches cover
 * all modes — English ON + Arabic ON = both together (one
 * merged file); one ON = that language only; both OFF = no
 * subtitles. When no Arabic file exists but English does,
 * the Arabic half is machine-translated from the English
 * file (labeled "مترجمة آلياً").
 * Volatile so the provider reads it without a Context;
 * persisted so it survives app restarts (restored in load()).
 */
object Anime9Subs {
    @Volatile
    var english: Boolean = true

    @Volatile
    var arabic: Boolean = true

    const val PREFS = "anime9_prefs"
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
object Anime9SubsUi {
    fun show(ctx: Context, prefs: SharedPreferences) {
        runCatching {
            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 32, 48, 8)
            }
            val hint = TextView(ctx).apply {
                text = "الترجمة الأوتوماتيكية للأنمي.\n" +
                    "تشغيل الاثنين معًا يعرض الإنجليزية والعربية " +
                    "في نفس الوقت (ملف ترجمة مدمج، والفيديو لا يُمس).\n" +
                    "إذا لم توجد ترجمة عربية وكان الإنجليزي متوفرًا، " +
                    "تُترجم العربية آليًا من الملف الإنجليزي."
            }
            val enToggle = Switch(ctx).apply {
                text = "الترجمة الإنجليزية"
                isChecked = Anime9Subs.english
            }
            val arToggle = Switch(ctx).apply {
                text = "الترجمة العربية"
                isChecked = Anime9Subs.arabic
            }
            layout.addView(hint)
            layout.addView(enToggle)
            layout.addView(arToggle)
            AlertDialog.Builder(ctx)
                .setTitle("إعدادات الترجمة — أنمي 9")
                .setView(layout)
                .setPositiveButton("حفظ") { _, _ ->
                    Anime9Subs.save(prefs, enToggle.isChecked, arToggle.isChecked)
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }
}

@CloudstreamPlugin
class Anime9Plugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(Anime9Subs.PREFS, Context.MODE_PRIVATE)
        Anime9Subs.loadFrom(prefs)
        openSettings = { ctx -> Anime9SubsUi.show(ctx, prefs) }
        registerMainAPI(Anime9Provider())
    }
}
