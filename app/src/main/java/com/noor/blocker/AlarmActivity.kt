package com.noor.blocker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast

/** صفحة الويب في وضع المنبّه */
private val NOOR_WEB_ALARM = NOOR_WEB + "#alarm=1"

/**
 * شاشة مهمّات المنبّه.
 *
 * تظهر فوق شاشة القفل وتُوقظ الشاشة، ولا تُغلَق إلا بإتمام المهمّات:
 * زرّ الرجوع لا يفعل شيئاً، والخروج منها يعيدها. الصوت ليس هنا بل في
 * MissionRinger، فلو قتل النظام هذه الشاشة ظلّ المنبّه يرنّ وظلّ
 * إشعارُه يعيدك إليها.
 *
 * المهمّات نفسها تجري داخل صفحة الويب (الكاميرا والتحقّق جاهزان
 * فيها)، وحين تنتهي تنادي NoorApp.alarmSolved فنُسكت الصوت ونُغلق.
 */
class AlarmActivity : Activity() {

    private var wake: PowerManager.WakeLock? = null
    private var web: WebView? = null
    private var solved = false
    private val handler = Handler(Looper.getMainLooper())

    inner class AlarmBridge {
        @android.webkit.JavascriptInterface
        fun alarmSolved() {
            solved = true
            MissionRinger.stop(this@AlarmActivity)
            EventLog.add(this@AlarmActivity, EventLog.EARN, "أتممتَ مهمّات المنبّه — قمتَ للفجر")
            handler.post {
                Toast.makeText(this@AlarmActivity, "قمتَ — تقبّل الله 🌅", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "noor:alarmScreen"
            ).apply { acquire(30 * 60 * 1000L) }
        } catch (_: Exception) {}

        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#04150F")) }

        web = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    if (request == null) return
                    // لا نمنح ما لا نملك: الوعد الكاذب يُعلّق getUserMedia للأبد
                    val held = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                        checkSelfPermission(android.Manifest.permission.CAMERA) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (held) request.grant(request.resources)
                    else {
                        request.deny()
                        Toast.makeText(
                            this@AlarmActivity,
                            "الكاميرا غير مسموحة — استخدم تحدّي الكتابة",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            addJavascriptInterface(AlarmBridge(), "NoorApp")
            loadUrl(NOOR_WEB_ALARM)
        }

        root.addView(web, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)
    }

    /** الخروج لا يُسكت المنبّه — نعيد الشاشة ما دام يرنّ */
    override fun onPause() {
        super.onPause()
        if (solved || isFinishing) return
        handler.postDelayed({
            if (!solved && !isFinishing && MissionRinger.ringing) {
                startActivity(
                    Intent(this, AlarmActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
        }, 700)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* لا مخرج إلا بالمهمّات */ }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
        web?.destroy()
        super.onDestroy()
    }
}
