package com.noor.blocker

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/* ───────── ثيم «الليل» ─────────
   كان أخضر وذهبياً كبقية التطبيق. والقفل يأتي في وقت صلاة قد يكون
   فجراً أو عشاءً، والعين فيه متعبة — فجعلناه زرقة ليل عميقة ولونَ
   مصباح دافئاً. مريح في العتمة، ومختلف عمّا اعتاده في كل تطبيق. */
private const val NIGHT_TOP = "#10131f"
private const val NIGHT_BOT = "#0a0c14"
private const val SAND = "#d9d3c7"      // ضوء المصباح — للأرقام والأزرار
private const val MOON = "#93a9dc"      // ضوء القمر — للحلقة
private const val INK = "#e8eaf2"
private const val FADE = "#8b92a8"

/**
 * شاشة القفل.
 *
 * حالتان: وقت صلاة، أو نفاد رصيد تحدّي الاستغفار.
 *
 * والكلام فيها قليل عمداً: حلقةٌ تُفرِغ ما بقي من الوقت، سطرٌ يقول
 * لماذا، وزرٌّ واحد. من يقرأ فقرةً كاملةً وهو ممنوع من هاتفه؟
 */
class LockActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var ring: RingView
    private lateinit var bigView: TextView
    private lateinit var titleView: TextView
    private lateinit var subView: TextView
    private lateinit var actionBtn: Button
    private lateinit var noteView: TextView

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    /** حلقة تُفرِغ مع الوقت — تُفهم بنظرة واحدة بلا قراءة */
    class RingView(ctx: android.content.Context) : View(ctx) {
        var progress = 0f           // ٠ إلى ١
        var color = Color.parseColor(MOON)
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#262c3e")
            strokeCap = Paint.Cap.ROUND
        }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val box = RectF()

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val stroke = w * 0.055f
            track.strokeWidth = stroke
            arc.strokeWidth = stroke
            val pad = stroke / 2 + w * 0.02f
            box.set(pad, pad, w - pad, height - pad)
            c.drawArc(box, 0f, 360f, false, track)
            arc.shader = LinearGradient(0f, 0f, w, height.toFloat(),
                color, Color.parseColor(SAND), Shader.TileMode.CLAMP)
            c.drawArc(box, -90f, 360f * progress.coerceIn(0f, 1f), false, arc)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = Color.parseColor(NIGHT_TOP)
            window.navigationBarColor = Color.parseColor(NIGHT_BOT)
        }

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(30), dp(26), dp(30), dp(26))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor(NIGHT_TOP), Color.parseColor(NIGHT_BOT))
            )
        }

        /* الحلقة والرقم بداخلها */
        ring = RingView(this)
        bigView = TextView(this).apply {
            textSize = 42f
            setTextColor(Color.parseColor(SAND))
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        val dial = FrameLayout(this).apply {
            addView(ring, FrameLayout.LayoutParams(dp(210), dp(210)))
            addView(bigView, FrameLayout.LayoutParams(
                dp(210), dp(210)).apply { gravity = Gravity.CENTER })
        }
        root.addView(dial, LinearLayout.LayoutParams(dp(210), dp(210)))

        titleView = TextView(this).apply {
            textSize = 23f
            setTextColor(Color.parseColor(INK))
            gravity = Gravity.CENTER
            setPadding(0, dp(26), 0, dp(4))
        }
        root.addView(titleView)

        subView = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor(FADE))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(30))
        }
        root.addView(subView)

        actionBtn = Button(this).apply {
            textSize = 17f
            setTextColor(Color.parseColor("#14171f"))
            setPadding(0, dp(15), 0, dp(15))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                colors = intArrayOf(Color.parseColor("#efe9de"), Color.parseColor(SAND))
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
            setOnClickListener { openUnlockTask() }
        }
        root.addView(actionBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        noteView = TextView(this).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#767e94"))
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        }
        root.addView(noteView)

        setContentView(root)
        hideSystemBars(root)
        render()
    }

    /** يفتح المهمة المناسبة: صلاة أمام الكاميرا أو تحدّي الضغطات */
    private fun openUnlockTask() {
        val lock = PrayerLock.current(this)
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (lock != null) {
            i.putExtra(EXTRA_OPEN_COACH, true)
            i.putExtra(EXTRA_RAKAAT, lock.rakaat)
        } else {
            i.putExtra(EXTRA_OPEN_CHALLENGE, true)
        }
        startActivity(i)
        finish()
    }

    private fun render() {
        val lock = PrayerLock.current(this)

        if (lock != null) {
            val total = Prefs.lockMinutes(this) * 60
            val left = lock.secondsLeft
            ring.color = Color.parseColor(MOON)
            ring.progress = if (total > 0) left.toFloat() / total else 0f
            ring.invalidate()

            bigView.text = String.format("%02d:%02d", left / 60, left % 60)
            titleView.text = "صلاة ${lock.prayerName}"
            subView.text = "${lock.rakaat} ركعات"
            actionBtn.text = "🕋 صلِّ مع الكاميرا"
            noteView.text = "الاتصال والطوارئ متاحة"
            return
        }

        if (ChallengeLock.active(this)) {
            ring.color = Color.parseColor("#C98ADE")
            ring.progress = 1f
            ring.invalidate()

            bigView.textSize = 34f
            bigView.text = "${ChallengeLock.reps(this)}\nضغطة"
            titleView.text = "نفد رصيدك"
            subView.text = "تكسب ${Credit.rewardMinutes(this)} دقيقة"
            actionBtn.text = "💪 اكسب رصيداً"
            noteView.text = "قل مع كل ضغطة: ${ChallengeLock.phrase(this)}"
            return
        }

        finish()
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
        const val EXTRA_OPEN_CHALLENGE = "open_challenge"
    }
}
