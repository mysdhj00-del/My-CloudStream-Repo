package com.cima4y

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * Auto English subtitles switch (v1, ported from the
 * Wecima2 / HorrorMovies gear — same "ترس الترجمة"): volatile
 * so the provider reads it without a Context; persisted so it
 * survives app restarts (restored in load()).
 */
object Cima4ySubs {
    @Volatile
    var autoEnglish: Boolean = true

    const val PREFS = "cima4y_prefs"
    const val KEY = "auto_english"

    fun loadFrom(prefs: SharedPreferences) {
        autoEnglish = prefs.getBoolean(KEY, true)
    }

    fun save(prefs: SharedPreferences, value: Boolean) {
        autoEnglish = value
        prefs.edit().putBoolean(KEY, value).apply()
    }
}

/** Code-built settings dialog (no resources): one switch. */
object Cima4ySubsUi {
    fun show(ctx: Context, prefs: SharedPreferences) {
        runCatching {
            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 32, 48, 8)
            }
            val hint = TextView(ctx).apply {
                text = "عند التشغيل، تظهر الترجمة الإنجليزية تلقائيًا " +
                    "مع كل فيلم أو حلقة (من مصدر مجاني، بدون مفتاح).\n" +
                    "عند الإيقاف، لا تُرفق أي ترجمة."
            }
            val toggle = Switch(ctx).apply {
                text = "الترجمة الإنجليزية التلقائية"
                isChecked = Cima4ySubs.autoEnglish
            }
            layout.addView(hint)
            layout.addView(toggle)
            AlertDialog.Builder(ctx)
                .setTitle("إعدادات Cima4y")
                .setView(layout)
                .setPositiveButton("حفظ") { _, _ ->
                    Cima4ySubs.save(prefs, toggle.isChecked)
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }
}

@CloudstreamPlugin
class Cima4yPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(Cima4ySubs.PREFS, Context.MODE_PRIVATE)
        Cima4ySubs.loadFrom(prefs)
        openSettings = { ctx -> Cima4ySubsUi.show(ctx, prefs) }
        registerMainAPI(Cima4yProvider())
    }
}
