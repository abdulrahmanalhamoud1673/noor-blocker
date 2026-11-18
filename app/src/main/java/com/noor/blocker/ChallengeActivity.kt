package com.noor.blocker

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * تحدّي الاستغفار — يعدّ الضغطات ويسمع الذكر.
 *
 * كان يعتمد على الكاميرا لرؤية الحركة وعلى تعرّف الكلام ليقبل الذكر،
 * فكان يفشل في الاثنين: الكاميرا تحتاج ضوءاً وإسناداً للهاتف وجسداً
 * كاملاً في الإطار، والتعرّف كان يُطلَق مرّةً لكل ضغطة فيضيع الكلام
 * في فجوة التشغيل.
 *
 * الآن: **حسّاس القرب** يعدّ الضغطات — تضع الهاتف أرضاً تحت صدرك،
 * فكلّما نزلتَ غطّيتَه وكلّما ارتفعتَ انكشف. يعمل في العتمة، بلا
 * كاميرا ولا نموذج، وهو ما تفعله تطبيقات الضغط الحقيقية.
 *
 * والميكروفون يبقى مفتوحاً طوال الجولة لا ضغطةً ضغطة، ويُظهر لك
 * شريطاً حيّاً يتحرّك مع صوتك — فترى بعينك أنه يسمعك. وهو **لا
 * يمنع** الضغطة من العدّ: الذكر لسانُك ونيّتُك، لا شهادةَ جهاز.
 */
class ChallengeActivity : Activity(), SensorEventListener {

    private val handler = Handler(Looper.getMainLooper())
    private var sensors: SensorManager? = null
    private var proximity: Sensor? = null
    private var recognizer: SpeechRecognizer? = null

    private var target = 10
    private var done = 0
    private var heard = 0
    private var covered = false
    private var coveredAt = 0L
    private var listening = false

    private lateinit var ring: RingView
    private lateinit var countView: TextView
    private lateinit var phraseView: TextView
    private lateinit var micBar: LevelView
    private lateinit var micNote: TextView
    private lateinit var hintView: TextView

    /* حلقة تمتلئ مع الضغطات */
    class RingView(ctx: Context) : View(ctx) {
        var progress = 0f
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.parseColor("#E2DACB"); strokeCap = Paint.Cap.ROUND
        }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.parseColor("#B07A46"); strokeCap = Paint.Cap.ROUND
        }
        private val box = RectF()
        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val s = w * 0.06f
            track.strokeWidth = s; arc.strokeWidth = s
            val pad = s / 2 + w * 0.02f
            box.set(pad, pad, w - pad, height - pad)
            c.drawArc(box, 0f, 360f, false, track)
            c.drawArc(box, -90f, 360f * progress.coerceIn(0f, 1f), false, arc)
        }
    }

    /* شريط يتحرّك مع صوتك — دليلٌ مرئي أن الميكروفون يسمع */
    class LevelView(ctx: Context) : View(ctx) {
        var level = 0f
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val h = height.toFloat()
            p.color = Color.parseColor("#F1ECE2")
            c.drawRoundRect(RectF(0f, 0f, width.toFloat(), h), h / 2, h / 2, p)
            p.color = Color.parseColor("#4A72A8")
            c.drawRoundRect(RectF(0f, 0f, width * level.coerceIn(0f, 1f), h), h / 2, h / 2, p)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = Color.parseColor("#F7F3EC")
            window.navigationBarColor = Color.parseColor("#EFE9DD")
        }

        target = ChallengeLock.reps(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(24))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#F7F3EC"), Color.parseColor("#EFE9DD"))
            )
        }

        phraseView = TextView(this).apply {
            text = ChallengeLock.phrase(this@ChallengeActivity)
            textSize = 27f
            setTextColor(Color.parseColor("#B07A46"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(26))
        }
        root.addView(phraseView)

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
            setPadding(0, dp(22), 0, dp(20))
        }
        root.addView(hintView)

        micBar = LevelView(this)
        root.addView(micBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))

        micNote = TextView(this).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#9A9183"))
            gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, 0)
        }
        root.addView(micNote)

        setContentView(root)

        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximity = sensors?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        if (proximity == null) {
            // لا حسّاس قرب: العدّ باللمس، والصدق على صاحبه
            hintView.text = "لا حسّاس قرب في هاتفك — المس الشاشة مع كل ضغطة"
            root.setOnClickListener { rep() }
        } else {
            hintView.text = "ضع الهاتف أرضاً تحت صدرك وابدأ"
        }

        render()
        askMic()
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
            // نزولٌ ثمّ صعود — ونشترط أن يبقى قريباً لحظةً كي لا
            // تُحسب رفرفةُ يدٍ عابرة ضغطةً
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
        micNote.text = if (!listening) "الميكروفون مغلق" else "سمعتك تذكر $heard مرة"
    }

    private fun finishChallenge() {
        val minutes = ChallengeLock.clearFor(this)
        stopListening()
        Toast.makeText(this, "أحسنت — كسبتَ $minutes دقيقة", Toast.LENGTH_LONG).show()
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    /* ─────────── السمع ─────────── */

    private fun askMic() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 9)
            return
        }
        startListening()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 9 && res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) startListening()
        else micNote.text = "بلا ميكروفون — الذكر بينك وبين ربّك"
    }

    /**
     * يبقى مفتوحاً طوال الجولة: كلّما انتهت نوبة أعدنا تشغيله فوراً،
     * فلا تضيع كلمةٌ في فجوة بين ضغطة وأخرى.
     */
    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            micNote.text = "هاتفك لا يدعم تمييز الكلام — عُدّ بالحركة فقط"
            return
        }
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onRmsChanged(v: Float) {
                        micBar.level = ((v + 2f) / 12f).coerceIn(0f, 1f)
                        micBar.invalidate()
                    }
                    override fun onPartialResults(b: Bundle?) = check(b)
                    override fun onResults(b: Bundle?) { check(b); restart() }
                    override fun onError(e: Int) = restart()
                    override fun onReadyForSpeech(b: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onBufferReceived(x: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(t: Int, b: Bundle?) {}
                })
            }
            listening = true
            listen()
        } catch (e: Exception) {
            micNote.text = "تعذّر فتح الميكروفون"
        }
    }

    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // لغةٌ كاملة لا «ar» وحدها: بعض الأجهزة ترفض الرمز المجرّد
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-JO")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try { recognizer?.startListening(i) } catch (_: Exception) {}
    }

    private fun restart() {
        if (!listening || isFinishing) return
        handler.postDelayed({ if (listening && !isFinishing) listen() }, 250)
    }

    private fun stopListening() {
        listening = false
        try { recognizer?.stopListening() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    private fun check(b: Bundle?) {
        val said = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.joinToString(" ") ?: return
        if (matches(said, ChallengeLock.phrase(this))) {
            heard++
            render()
        }
    }

    /** مقارنة متسامحة: نجرّد التشكيل ونقبل نصف الكلمات */
    private fun matches(said: String, phrase: String): Boolean {
        fun strip(s: String) = s
            .replace(Regex("[ً-ْٰـ]"), "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
            .replace('ة', 'ه').replace('ى', 'ي').trim()
        val heardTxt = strip(said)
        val want = strip(phrase).split(Regex("\\s+")).filter { it.length > 2 }
        if (want.isEmpty()) return heardTxt.isNotEmpty()
        return want.count { heardTxt.contains(it) } >= (want.size + 1) / 2
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

    override fun onDestroy() {
        stopListening()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
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
