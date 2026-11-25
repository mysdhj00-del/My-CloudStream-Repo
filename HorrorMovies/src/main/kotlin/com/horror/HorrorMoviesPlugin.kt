package com.horror

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * Auto English subtitles switch (v6 settings): volatile so the
 * provider reads it without a Context; persisted so it survives
 * app restarts (restored in load()).
 */
object HorrorSubs {
    @Volatile
    var autoEnglish: Boolean = true

    const val PREFS = "horror_prefs"
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
object HorrorSubsUi {
    fun show(ctx: Context, prefs: SharedPreferences) {
        runCatching {
            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 32, 48, 8)
            }
            val hint = TextView(ctx).apply {
                text = "عند التشغيل، تظهر الترجمة الإنجليزية تلقائيًا " +
                    "مع كل فيلم (من مصدر مجاني، بدون مفتاح).\n" +
                    "عند الإيقاف، لا تُرفق أي ترجمة."
            }
            val toggle = Switch(ctx).apply {
                text = "الترجمة الإنجليزية التلقائية"
                isChecked = HorrorSubs.autoEnglish
            }
            layout.addView(hint)
            layout.addView(toggle)
            AlertDialog.Builder(ctx)
                .setTitle("إعدادات أفلام رعب")
                .setView(layout)
                .setPositiveButton("حفظ") { _, _ ->
                    HorrorSubs.save(prefs, toggle.isChecked)
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
