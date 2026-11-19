package com.noor.blocker

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/** رابط صفحة الأذكار والقرآن */
const val NOOR_WEB = "https://abdulrahmanalhamoud1673.github.io/noor-adhkar/"

/** يُستخدم لمسح ذاكرة الويب مرة واحدة بعد كل تحديث */
private const val APP_VERSION = "17.0"

/**
 * نور — أذكار الصباح والمساء والقرآن.
 *
 * شاشةٌ واحدة: صفحة الأذكار والقرآن، ومعها تنبيه الأذان في وقته.
 *
 * وكان التطبيق قبلها يحظر تطبيقات التواصل وقت الصلاة عبر خدمة
 * إمكانية الوصول وصلاحية مدير الجهاز. وهما بابا الرفض الأكبر في
 * جوجل بلاي: الأولى مقصورة على أدوات ذوي الاحتياجات، والثانية
 * تُفحص بشدّة. فحُذف الحظر كلّه ليصل التطبيق إلى الناس.
 */
class MainActivity : Activity() {

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        PrayerAlarm.createChannel(this)
        PrayerAlarm.schedule(this)
        askNotifications()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#F7F3EC"))
        }

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            setBackgroundColor(Color.parseColor("#F7F3EC"))

            // بعد كل تحديث نمسح ذاكرة الصفحة مرّة واحدة، وإلا خلط
            // المتصفّح ملفّاً قديماً بآخر جديد فتعطّلت الصفحة
            if (Prefs.lastWebClean(this@MainActivity) != APP_VERSION) {
                clearCache(true)
                Prefs.setLastWebClean(this@MainActivity, APP_VERSION)
            }
            loadUrl(NOOR_WEB)
        }

        root.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    /** إذن الإشعارات لازمٌ لتنبيه الأذان على أندرويد ١٣ فما فوق */
    private fun askNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5)
    }

    /** زرّ الرجوع يتنقّل داخل الصفحة قبل أن يُغلق التطبيق */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = webView
        if (w != null && w.canGoBack()) w.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
