package com.noor.blocker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * تحدّي الاستغفار — يعدّ الضغطات بحسّاس القرب.
 *
 * تضع الهاتف أرضاً تحت صدرك، فكلّما نزلتَ غطّيتَه وكلّما ارتفعتَ
 * انكشف. يعمل في العتمة، بلا كاميرا ولا نموذج — وهو ما تفعله
 * تطبيقات الضغط الحقيقية.
 *
 * ولا ميكروفون: كان يُنصت للذكر ثمّ أُلغي شرطُ النطق، فلم يبقَ له
 * عمل. وحذفه أسقط إذن تسجيل الصوت من التطبيق كلّه. والذكر لسانُك
 * ونيّتُك، لا شهادةَ جهاز.
 */
class ChallengeActivity : Activity(), SensorEventListener {

    private var sensors: SensorManager? = null
    private var proximity: Sensor? = null

    private var target = 10
    private var done = 0
    private var covered = false
    private var coveredAt = 0L

    private lateinit var ring: RingView
    private lateinit var countView: TextView
    private lateinit var hintView: TextView

    /** حلقة تمتلئ مع الضغطات */
    class RingView(ctx: Context) : View(ctx) {
        var progress = 0f
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#E2DACB")
            strokeCap = Paint.Cap.ROUND
        }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#8E5C2C")
            strokeCap = Paint.Cap.ROUND
        }
        private val box = RectF()

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val s = w * 0.055f
            track.strokeWidth = s
            arc.strokeWidth = s
            val pad = s / 2 + w * 0.02f
            box.set(pad, pad, w - pad, height - pad)
            c.drawArc(box, 0f, 360f, false, track)
            c.drawArc(box, -90f, 360f * progress.coerceIn(0f, 1f), false, arc)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = Color.parseColor("#FBF8F2")
            window.navigationBarColor = Color.parseColor("#EFE9DD")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        target = ChallengeLock.reps(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(30), dp(26), dp(30), dp(26))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#FBF8F2"), Color.parseColor("#EFE9DD"))
            )
        }

        root.addView(TextView(this).apply {
            text = ChallengeLock.phrase(this@ChallengeActivity)
            textSize = 26f
            setTextColor(Color.parseColor("#8E5C2C"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(26))
        })

        ring = RingView(this)
        countView = TextView(this).apply {
            textSize = 46f
            setTextColor(Color.parseColor("#2B2A31"))
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        root.addView(FrameLayout(this).apply {
            addView(ring, FrameLayout.LayoutParams(dp(200), dp(200)))
            addView(countView, FrameLayout.LayoutParams(dp(200), dp(200))
                .apply { gravity = Gravity.CENTER })
        }, LinearLayout.LayoutParams(dp(200), dp(200)))

        hintView = TextView(this).apply {
            textSize = 14.5f
            setTextColor(Color.parseColor("#7B7468"))
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        }
        root.addView(hintView)

        setContentView(root)

        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximity = sensors?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        if (proximity == null) {
            hintView.text = "لا حسّاس قرب — المس الشاشة مع كل ضغطة"
            root.setOnClickListener { rep() }
        } else {
            hintView.text = "ضع الهاتف أرضاً تحت صدرك"
        }
        render()
    }

    /* ─────────── العدّ ─────────── */

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_PROXIMITY) return
        val max = proximity?.maximumRange ?: 5f
        val near = e.values[0] < (max * 0.5f).coerceAtMost(5f)

        if (near && !covered) {
            covered = true
            coveredAt = System.currentTimeMillis()
        } else if (!near && covered) {
            covered = false
            // نزولٌ ثمّ صعود، ويبقى قريباً لحظةً كي لا تُحسب
            // رفرفةُ يدٍ عابرة ضغطةً
            if (System.currentTimeMillis() - coveredAt in 120..6000) rep()
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private fun rep() {
        if (done >= target) return
        done++
        vibrate()
        render()
        if (done >= target) finishChallenge()
    }

    private fun vibrate() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(android.os.VibrationEffect.createOneShot(35, 120))
            } else {
                @Suppress("DEPRECATION") v.vibrate(35)
            }
        } catch (_: Exception) {}
    }

    private fun render() {
        countView.text = "$done"
        ring.progress = if (target > 0) done.toFloat() / target else 0f
        ring.invalidate()
        if (done > 0 && done < target) hintView.text = "بقي ${target - done}"
    }

    private fun finishChallenge() {
        val minutes = ChallengeLock.clearFor(this)
        Toast.makeText(this, "أحسنت — كسبتَ $minutes دقيقة", Toast.LENGTH_LONG).show()
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    /* ─────────── دورة الحياة ─────────── */

    override fun onResume() {
        super.onResume()
        proximity?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun onPause() {
        super.onPause()
        sensors?.unregisterListener(this)
    }

    /** لا هروب من التحدّي بزرّ الرجوع */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }
}
