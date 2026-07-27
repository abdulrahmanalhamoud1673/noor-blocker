package com.noor.blocker

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** شاشة القفل التي تظهر فوق التطبيق المحظور */
class LockActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var titleView: TextView
    private lateinit var countdownView: TextView

    private val ticker = object : Runnable {
        override fun run() {
            val lock = PrayerLock.current(this@LockActivity)
            if (lock == null) {
                finish()
                return
            }
            titleView.text = "حان وقت صلاة ${lock.prayerName}"
            val m = lock.secondsLeft / 60
            val s = lock.secondsLeft % 60
            countdownView.text = String.format("%02d:%02d", m, s)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#04150F"))
            setPadding(60, 60, 60, 60)
        }

        root.addView(TextView(this).apply {
            text = "🕌"
            textSize = 64f
            gravity = Gravity.CENTER
        })

        titleView = TextView(this).apply {
            textSize = 27f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 14)
        }
        root.addView(titleView)

        root.addView(TextView(this).apply {
            text = "﴿ إِنَّ الصَّلَاةَ كَانَتْ عَلَى الْمُؤْمِنِينَ كِتَابًا مَّوْقُوتًا ﴾"
            textSize = 16f
            setTextColor(Color.parseColor("#9DBDB0"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        })

        countdownView = TextView(this).apply {
            textSize = 46f
            setTextColor(Color.parseColor("#F0D98A"))
            gravity = Gravity.CENTER
        }
        root.addView(countdownView)

        root.addView(TextView(this).apply {
            text = "الوقت المتبقي على فتح التطبيقات"
            textSize = 13f
            setTextColor(Color.parseColor("#9DBDB0"))
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 46)
        })

        root.addView(Button(this).apply {
            text = "صلّيت — افتح التطبيقات ✓"
            textSize = 17f
            setOnClickListener {
                PrayerLock.current(this@LockActivity)?.let {
                    Prefs.markPrayed(this@LockActivity, it.key)
                }
                goHome()
                finish()
            }
        })

        root.addView(TextView(this).apply {
            text = "اضغط الزر فقط بعد أن تُصلّي فعلاً"
            textSize = 12f
            setTextColor(Color.parseColor("#6E8F82"))
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 0)
        })

        setContentView(root)
        hideSystemBars(root)
    }

    private fun hideSystemBars(v: View) {
        @Suppress("DEPRECATION")
        v.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun goHome() {
        val home = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_HOME)
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(home)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    /** زر الرجوع لا يخرجك — يرجعك للشاشة الرئيسية بدل التطبيق المحظور */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        goHome()
    }
}
