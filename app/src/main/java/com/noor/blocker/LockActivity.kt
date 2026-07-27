package com.noor.blocker

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * شاشة القفل التي تظهر فوق التطبيق المحظور.
 *
 * لا يوجد زر «صلّيت». الطريق الوحيد لفكّ القفل أن تؤدّي الصلاة
 * كاملة أمام الكاميرا: بعدد ركعاتها، وبترتيب حركاتها، وبزمن
 * معقول. عند التسليم يُفتح القفل وحده — وإلا يبقى مقفلاً حتى
 * ينتهي وقت القفل.
 */
class LockActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var titleView: TextView
    private lateinit var stateView: TextView
    private lateinit var timerView: TextView

    private val ticker = object : Runnable {
        override fun run() {
            render()
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

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#04150F"))
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }

        root.addView(TextView(this).apply {
            text = "🕌"; textSize = 56f; gravity = Gravity.CENTER
        })

        titleView = TextView(this).apply {
            textSize = 25f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(8))
        }
        root.addView(titleView)

        root.addView(TextView(this).apply {
            text = "﴿ إِنَّ الصَّلَاةَ كَانَتْ عَلَى الْمُؤْمِنِينَ كِتَابًا مَّوْقُوتًا ﴾"
            textSize = 15f
            setTextColor(Color.parseColor("#9DBDB0"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
        })

        stateView = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#9DBDB0"))
            gravity = Gravity.CENTER
        }
        root.addView(stateView)

        timerView = TextView(this).apply {
            textSize = 40f
            setTextColor(Color.parseColor("#F0D98A"))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(26))
        }
        root.addView(timerView)

        /* الطريق الوحيد */
        root.addView(Button(this).apply {
            text = "🕋 صلِّ أمام الكاميرا"
            textSize = 18f
            setBackgroundColor(Color.parseColor("#D4AF37"))
            setTextColor(Color.parseColor("#16130A"))
            setPadding(0, dp(16), 0, dp(16))
            setOnClickListener {
                val lock = PrayerLock.current(this@LockActivity)
                startActivity(Intent(this@LockActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(EXTRA_OPEN_COACH, true)
                    putExtra(EXTRA_RAKAAT, lock?.rakaat ?: 4)
                })
                finish()
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        root.addView(TextView(this).apply {
            text = "لا يوجد زر آخر لفكّ القفل.\n" +
                   "أدِّ الصلاة كاملة أمام الكاميرا، وعند التسليم يُفتح وحده."
            textSize = 12.5f
            setTextColor(Color.parseColor("#6E8F82"))
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
            setLineSpacing(0f, 1.5f)
        })

        setContentView(root)
        hideSystemBars(root)
        render()
    }

    private fun render() {
        val lock = PrayerLock.current(this)
        if (lock == null) { finish(); return }

        titleView.text = "حان الآن وقت صلاة ${lock.prayerName}"
        stateView.text = "${lock.rakaat} ركعات · ينتهي القفل بعد"
        val s = lock.secondsLeft
        timerView.text = String.format("%02d:%02d", s / 60, s % 60)
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
        startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    /** زر الرجوع لا يفتح القفل — يرجعك للشاشة الرئيسية */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { goHome() }

    companion object {
        const val EXTRA_OPEN_COACH = "open_coach"
        const val EXTRA_RAKAAT = "rakaat"
    }
}
