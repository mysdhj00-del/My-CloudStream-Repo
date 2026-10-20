package com.ytube

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/** Holds the signed-in YouTube cookies (set from the plugin settings). */
object YtAuth {
    @Volatile
    var cookies: String = ""

    const val PREFS = "ytube_auth"
    const val KEY = "cookies"

    fun loadFrom(prefs: SharedPreferences) {
        cookies = prefs.getString(KEY, "") ?: ""
    }

    fun save(prefs: SharedPreferences, value: String) {
        cookies = value.trim()
        prefs.edit().putString(KEY, cookies).apply()
    }

    fun clear(prefs: SharedPreferences) {
        cookies = ""
        prefs.edit().remove(KEY).apply()
    }

    fun signedIn(): Boolean = cookies.contains("SAPISID")
}

/**
 * Sign-in UI (code-built, no resources): a WebView that opens the
 * real Google/YouTube sign-in page — the user signs in THEMSELVES,
 * the resulting youtube.com cookies are stored locally — or a
 * paste field for cookies copied from their own browser.
 * The password never touches this plugin.
 */
object YtAuthUi {
    fun show(ctx: Context, prefs: SharedPreferences) {
        runCatching {
            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 32, 48, 8)
            }
            val status = TextView(ctx).apply {
                text = if (YtAuth.signedIn()) {
                    "الحالة: مسجل الدخول ✓\nقسم «قوائمي» في الإضافة يعرض قوائم تشغيل حسابك."
                } else {
                    "الحالة: غير مسجل.\nسجّل دخولك بحساب يوتيوب الأصلي عشان قوائمك الشخصية تظهر في قسم «قوائمي»."
                }
            }
            layout.addView(status)
            val input = EditText(ctx).apply {
                hint = "أو الصق كوكيز يوتيوب هنا (من متصفحك)"
                setText(YtAuth.cookies)
                maxLines = 4
            }
            layout.addView(input)
            AlertDialog.Builder(ctx)
                .setTitle("حساب يوتيوب")
                .setView(layout)
                .setPositiveButton("فتح تسجيل الدخول") { _, _ ->
                    openWebLogin(ctx, prefs)
                }
                .setNeutralButton("حفظ الكوكيز") { _, _ ->
                    YtAuth.save(prefs, input.text.toString())
                    Toast.makeText(ctx, "تم الحفظ", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("تسجيل الخروج") { _, _ ->
                    YtAuth.clear(prefs)
                    Toast.makeText(ctx, "تم تسجيل الخروج", Toast.LENGTH_SHORT).show()
                }
                .show()
        }.onFailure {
            Toast.makeText(ctx, "تعذر فتح الإعدادات", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWebLogin(ctx: Context, prefs: SharedPreferences) {
        runCatching {
            val wv = WebView(ctx)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true) }
            // A WebView inside a dialog does not grab touch focus by
            // itself on several OEM skins — without it the soft
            // keyboard never opens when the user taps a field.
            wv.isFocusable = true
            wv.isFocusableInTouchMode = true
            wv.setOnTouchListener { v, _ ->
                (v as WebView).requestFocusFromTouch()
                false
            }
            val dialog = AlertDialog.Builder(ctx)
                .setView(wv)
                .setNegativeButton("إغلاق", null)
                .create()
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url != null && url.startsWith("https://www.youtube.com")) {
                        val c = CookieManager.getInstance()
                            .getCookie("https://www.youtube.com") ?: ""
                        if (c.contains("SAPISID")) {
                            YtAuth.save(prefs, c)
                            Toast.makeText(
                                ctx, "تم تسجيل الدخول بنجاح ✓",
                                Toast.LENGTH_LONG
                            ).show()
                            runCatching { dialog.dismiss() }
                        }
                    }
                }
            }
            dialog.setOnShowListener {
                dialog.window?.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                )
                dialog.window?.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                )
                wv.requestFocusFromTouch()
            }
            dialog.show()
            wv.loadUrl(
                "https://accounts.google.com/ServiceLogin?service=youtube" +
                    "&continue=https%3A%2F%2Fwww.youtube.com%2F"
            )
        }.onFailure {
            Toast.makeText(ctx, "تعذر فتح صفحة الدخول", Toast.LENGTH_SHORT).show()
        }
    }
}

@CloudstreamPlugin
class YouTubePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(YouTubeProvider())
        val prefs = context.getSharedPreferences(YtAuth.PREFS, Context.MODE_PRIVATE)
        YtAuth.loadFrom(prefs)
        openSettings = { ctx -> YtAuthUi.show(ctx, prefs) }
    }
}
