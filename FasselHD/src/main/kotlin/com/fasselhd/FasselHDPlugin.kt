package com.fasselhd

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * Auto English subtitles switch (v6, ported from the
 * Wecima2 addon — same "ترس الترجمة" gear): volatile so
 * the provider reads it without a Context; persisted so it
 * survives app restarts (restored in load()).
 */
object FasselHDSubs {
    @Volatile
    var autoEnglish: Boolean = true

    const val PREFS = "fasselhd_prefs"
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
object FasselHDSubsUi {
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
                isChecked = FasselHDSubs.autoEnglish
            }
            layout.addView(hint)
            layout.addView(toggle)
            AlertDialog.Builder(ctx)
                .setTitle("إعدادات FasselHD")
                .setView(layout)
                .setPositiveButton("حفظ") { _, _ ->
                    FasselHDSubs.save(prefs, toggle.isChecked)
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }
}

@CloudstreamPlugin
class FasselHDPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(FasselHDSubs.PREFS, Context.MODE_PRIVATE)
        FasselHDSubs.loadFrom(prefs)
        openSettings = { ctx -> FasselHDSubsUi.show(ctx, prefs) }
        registerMainAPI(FasselHDProvider())
    }
}
