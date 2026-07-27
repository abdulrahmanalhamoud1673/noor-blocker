package com.noor.blocker

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * شاشة القفل التي تظهر فوق التطبيق المحظور.
 *
 * لا تُفتح بضغطة واحدة عمداً. أمامك طريقان:
 *  ١) صلِّ أمام الكاميرا — يفتح فور التسليم لأنه إثبات فعلي.
 *  ٢) صلِّ عادةً — لا يظهر زر الفتح إلا بعد مدة تكفي لأداء الصلاة،
 *     ثم يلزمك ضغط مطوّل مع إقرار، فلا يكون الفتح بحركة عابرة.
 */
class LockActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var titleView: TextView
    private lateinit var stateView: TextView
    private lateinit var timerView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var unlockBtn: Button
    private lateinit var holdHint: TextView

    private var holdStart = 0L
    private var holding = false
    private val HOLD_MS = 3000L

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500)
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
            setPadding(0, dp(14), 0, dp(10))
        }
        root.addView(titleView)

        root.addView(TextView(this).apply {
            text = "﴿ إِنَّ الصَّلَاةَ كَانَتْ عَلَى الْمُؤْمِنِينَ كِتَابًا مَّوْقُوتًا ﴾"
            textSize = 15f
            setTextColor(Color.parseColor("#9DBDB0"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(26))
        })

        /* حالة الانتظار */
        stateView = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#9DBDB0"))
            gravity = Gravity.CENTER
        }
        root.addView(stateView)

        timerView = TextView(this).apply {
            textSize = 44f
            setTextColor(Color.parseColor("#F0D98A"))
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(10))
        }
        root.addView(timerView)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
        }
        root.addView(progress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(8)
        ).apply { bottomMargin = dp(28) })

        /* الطريق الأول: إثبات بالكاميرا */
        root.addView(Button(this).apply {
            text = "🕋 صلِّ أمام الكاميرا — فتح فوري"
            textSize = 16f
            setBackgroundColor(Color.parseColor("#0A3227"))
            setTextColor(Color.parseColor("#EAF5F0"))
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener {
                startActivity(Intent(this@LockActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(EXTRA_OPEN_COACH, true)
                })
                finish()
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })

        /* الطريق الثاني: ضغط مطوّل بعد انقضاء المدة */
        unlockBtn = Button(this).apply {
            textSize = 16f
            setPadding(0, dp(14), 0, dp(14))
            setOnTouchListener { _, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> { beginHold(); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { endHold(); true }
                    else -> false
                }
            }
        }
        root.addView(unlockBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        holdHint = TextView(this).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#6E8F82"))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(holdHint)

        setContentView(root)
        hideSystemBars(root)
        render()
    }

    /* ─────────── الضغط المطوّل ─────────── */
    private fun beginHold() {
        val lock = PrayerLock.current(this) ?: return
        if (!lock.mayUnlock) {
            Toast.makeText(this, "لم يمضِ وقت يكفي لأداء الصلاة بعد", Toast.LENGTH_SHORT).show()
            return
        }
        holding = true
        holdStart = System.currentTimeMillis()
        handler.post(holdTick)
    }

    private fun endHold() {
        holding = false
        handler.removeCallbacks(holdTick)
        render()
    }

    private val holdTick = object : Runnable {
        override fun run() {
            if (!holding) return
            val held = System.currentTimeMillis() - holdStart
            if (held >= HOLD_MS) {
                holding = false
                confirmPrayed()
                return
            }
            val left = ((HOLD_MS - held) / 1000.0) + 0.05
            unlockBtn.text = String.format("استمر بالضغط… %.1f", left)
            handler.postDelayed(this, 60)
        }
    }

    private fun confirmPrayed() {
        PrayerLock.current(this)?.let { Prefs.markPrayed(this, it.key) }
        Toast.makeText(this, "تقبّل الله منك 🤲", Toast.LENGTH_LONG).show()
        goHome()
        finish()
    }

    /* ─────────── الرسم ─────────── */
    private fun render() {
        val lock = PrayerLock.current(this)
        if (lock == null) { finish(); return }

        titleView.text = "حان الآن وقت صلاة ${lock.prayerName}"

        if (!lock.mayUnlock) {
            // ما زال في وقت الصلاة — لا نعرض زر فتح أصلاً
            stateView.text = "صلِّ الآن — ${lock.rakaat} ركعات"
            val s = lock.untilUnlock
            timerView.text = String.format("%02d:%02d", s / 60, s % 60)
            progress.progress = (1000 * lock.elapsed / lock.minSeconds).coerceIn(0, 1000)

            unlockBtn.isEnabled = false
            unlockBtn.text = "🔒 يُفتح بعد انقضاء وقت الصلاة"
            unlockBtn.setBackgroundColor(Color.parseColor("#123329"))
            unlockBtn.setTextColor(Color.parseColor("#5E7F72"))
            holdHint.text = "لا يمكن الفتح قبل أن يمضي وقت يكفي لأدائها"
        } else {
            stateView.text = "انقضى وقت يكفي لأداء الصلاة"
            val s = lock.secondsLeft
            timerView.text = String.format("%02d:%02d", s / 60, s % 60)
            progress.progress = 1000

            unlockBtn.isEnabled = true
            if (!holding) unlockBtn.text = "أشهد أنّي صلّيت — اضغط مطوّلاً"
            unlockBtn.setBackgroundColor(Color.parseColor("#D4AF37"))
            unlockBtn.setTextColor(Color.parseColor("#16130A"))
            holdHint.text = "استمر بالضغط ٣ ثوانٍ — واجعلها صدقاً بينك وبين الله"
        }
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
        handler.removeCallbacks(holdTick)
        holding = false
    }

    /** زر الرجوع لا يفتح القفل — يرجعك للشاشة الرئيسية */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { goHome() }

    companion object {
        const val EXTRA_OPEN_COACH = "open_coach"
    }
}
