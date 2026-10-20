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

            // Native credential bridge (his request): two NATIVE
            // fields that accept typing AND paste sit above the
            // WebView; a fill button writes the value into the
            // Google page itself via JS. The password is never
            // stored or sent anywhere — it only fills the official
            // page's field, exactly like typing it there.
            val emailField = EditText(ctx).apply {
                hint = "الإيميل (اكتب أو الصق هنا)"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                visibility = android.view.View.GONE
            }
            layout.addView(emailField)
            val passField = EditText(ctx).apply {
                hint = "كلمة السر (اكتب أو الصق هنا)"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                visibility = android.view.View.GONE
            }
            layout.addView(passField)
            val fillRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                visibility = android.view.View.GONE
            }
            val btnEmail = android.widget.Button(ctx).apply {
                text = "تعبئة الإيميل"
            }
            val btnPass = android.widget.Button(ctx).apply {
                text = "تعبئة كلمة السر"
            }
            fillRow.addView(btnEmail)
            fillRow.addView(btnPass)
            layout.addView(fillRow)

            // The login WebView lives INSIDE this same dialog from
            // the start (hidden). Opening a SECOND dialog for it made
            // the soft keyboard never appear on the login page —
            // a fresh dialog window loses the IME binding, and
            // AlertDialog's FLAG_ALT_FOCUSABLE_IM blocks the rest.
            // One window throughout + clearing that flag fixes it.
            val wv = WebView(ctx)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true) }
            wv.isFocusable = true
            wv.isFocusableInTouchMode = true
            wv.setOnTouchListener { v, _ ->
                (v as WebView).requestFocusFromTouch()
                false
            }
            wv.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1000
            )
            wv.visibility = android.view.View.GONE
            layout.addView(wv)

            val dialog = AlertDialog.Builder(ctx)
                .setTitle("حساب يوتيوب")
                .setView(layout)
                .setPositiveButton("فتح تسجيل الدخول", null)
                .setNeutralButton("حفظ الكوكيز") { _, _ ->
                    YtAuth.save(prefs, input.text.toString())
                    Toast.makeText(ctx, "تم الحفظ", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("تسجيل الخروج") { _, _ ->
                    YtAuth.clear(prefs)
                    Toast.makeText(ctx, "تم تسجيل الخروج", Toast.LENGTH_SHORT).show()
                }
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

            // Fill a field on the Google page with a value from the
            // native bridge fields (typing/paste happens natively).
            fun fillInto(selector: String, value: String, label: String, after: () -> Unit) {
                val js = "(function(){var el=document.querySelector('" + selector + "');" +
                    "if(!el)return 'nf';" +
                    "var st=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;" +
                    "st.call(el," + org.json.JSONObject.quote(value) + ");" +
                    "el.dispatchEvent(new Event('input',{bubbles:true}));" +
                    "el.dispatchEvent(new Event('change',{bubbles:true}));" +
                    "el.focus();return 'ok';})()"
                wv.evaluateJavascript(js) { res ->
                    if (res != null && res.contains("ok")) {
                        Toast.makeText(
                            ctx, "تمت تعبئة $label ✓ — دوس «التالي» في الصفحة",
                            Toast.LENGTH_LONG
                        ).show()
                        after()
                    } else {
                        Toast.makeText(
                            ctx, "خانة $label مش ظاهرة لسه — استنى الصفحة تحمّل",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            btnEmail.setOnClickListener {
                val v = emailField.text.toString().trim()
                if (v.isEmpty()) {
                    Toast.makeText(ctx, "الصق الإيميل في المربع الأول", Toast.LENGTH_SHORT).show()
                } else {
                    fillInto("input[type=email]", v, "الإيميل") {}
                }
            }
            btnPass.setOnClickListener {
                val v = passField.text.toString()
                if (v.isEmpty()) {
                    Toast.makeText(ctx, "الصق كلمة السر في المربع التاني", Toast.LENGTH_SHORT).show()
                } else {
                    fillInto("input[type=password]", v, "كلمة السر") {
                        passField.setText("")
                    }
                }
            }

            dialog.setOnShowListener {
                dialog.window?.clearFlags(
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                )
                dialog.window?.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                )
                dialog.window?.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                )
                // Override so the button swaps the view in-place
                // instead of dismissing the dialog.
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener {
                        status.visibility = android.view.View.GONE
                        input.visibility = android.view.View.GONE
                        emailField.visibility = android.view.View.VISIBLE
                        passField.visibility = android.view.View.VISIBLE
                        fillRow.visibility = android.view.View.VISIBLE
                        wv.visibility = android.view.View.VISIBLE
                        dialog.window?.clearFlags(
                            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                        )
                        wv.requestFocusFromTouch()
                        wv.loadUrl(
                            "https://accounts.google.com/ServiceLogin?service=youtube" +
                                "&continue=https%3A%2F%2Fwww.youtube.com%2F"
                        )
                    }
            }
            dialog.show()
        }.onFailure {
            Toast.makeText(ctx, "تعذر فتح الإعدادات", Toast.LENGTH_SHORT).show()
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
