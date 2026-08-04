package com.noor.blocker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** رابط تطبيق الأذكار والقرآن */
private const val NOOR_WEB = "https://abdulrahmanalhamoud1673.github.io/noor-adhkar/"

/** يُستخدم لمسح ذاكرة الويب مرة واحدة بعد كل تحديث */
private const val APP_VERSION = "7.0"

/** رمز طلب أذونات الوسائط (كاميرا / ميكروفون) من داخل الصفحة */
private const val REQ_MEDIA = 11

/**
 * جسر بين صفحة الأذكار ومحرّك التطبيق.
 * يستدعيه مدرّب الصلاة عند التسليم فيفكّ قفل هذه الصلاة —
 * وهذا هو الإثبات الحقيقي الوحيد: أن تصلّي فعلاً أمام الكاميرا.
 */
class NoorBridge(
    private val ctx: android.content.Context,
    private val onListen: (String) -> Unit
) {
    @android.webkit.JavascriptInterface
    fun prayerCompleted() {
        val lock = PrayerLock.current(ctx) ?: return
        Prefs.markPrayed(ctx, lock.key)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(ctx, "تقبّل الله منك — فُتح القفل 🤲", Toast.LENGTH_LONG).show()
        }
    }

    /** أتمّ تحدّي الاستغفار — يُضاف الرصيد */
    @android.webkit.JavascriptInterface
    fun challengeCompleted() {
        val mins = Credit.award(ctx)
        val total = Credit.format(ctx)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(ctx, "أحسنت — كسبتَ $mins دقيقة · رصيدك $total", Toast.LENGTH_LONG).show()
        }
    }

    /** يطلب من أندرويد الإنصات والتحقق من نطق الذكر */
    @android.webkit.JavascriptInterface
    fun listenForDhikr(phrase: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { onListen(phrase) }
    }
}

/** التطبيقات الأكثر تشتيتاً — لاختيارها بضغطة واحدة */
private val COMMON_DISTRACTIONS = listOf(
    "com.instagram.android",
    "com.zhiliaoapp.musically",      // TikTok
    "com.ss.android.ugc.trill",      // TikTok (نسخة أخرى)
    "com.google.android.youtube",
    "com.facebook.katana",
    "com.snapchat.android",
    "com.twitter.android",
    "com.x.android",
    "com.netflix.mediaclient",
    "com.reddit.frontpage",
    "org.telegram.messenger"
)

class MainActivity : Activity() {

    private lateinit var content: FrameLayout
    private lateinit var tabWeb: Button
    private lateinit var tabBlock: Button

    private var webView: WebView? = null
    private var blockerView: ScrollView? = null

    private lateinit var statusView: TextView
    private lateinit var timesView: TextView
    private lateinit var countView: TextView
    private lateinit var appsBox: LinearLayout

    private val selected = HashSet<String>()
    private var allApps: List<ApplicationInfo> = emptyList()
    private var filter = ""

    private lateinit var banner: TextView
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateBanner()
            ticker.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected.addAll(Prefs.blockedApps(this))

        // إذن الإشعارات مطلوب صراحةً من أندرويد ١٣
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 2)
        }
        PrayerAlarm.schedule(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#04150F"))
        }

        root.addView(buildBanner())
        root.addView(buildTabs())

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
        showWeb()

        // قادم من شاشة القفل
        if (intent?.getBooleanExtra(LockActivity.EXTRA_OPEN_COACH, false) == true) {
            openCoach(intent.getIntExtra(LockActivity.EXTRA_RAKAAT, 4))
        } else if (intent?.getBooleanExtra(LockActivity.EXTRA_OPEN_CHALLENGE, false) == true) {
            openChallenge()
        }
    }

    /**
     * بعد ردّ المستخدم على طلب الإذن.
     * إن منح الكاميرا نُعيد تحميل الصفحة، وإلا بقيت محاولة سابقة فاشلة
     * عالقة في ذاكرتها فيظنّ أن الإذن لم ينفع.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != REQ_MEDIA && requestCode != 1) return

        val granted = permissions.indices.any { i ->
            permissions[i] == Manifest.permission.CAMERA &&
            results.getOrNull(i) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) {
            Toast.makeText(this, "تم منح الكاميرا — اضغط «ابدأ» الآن", Toast.LENGTH_LONG).show()
            webView?.reload()
        }
    }

    /**
     * يفتح مدرّب الصلاة في وضع فكّ القفل.
     * نمرّر عدد الركعات في الرابط فتُثبَّت الصلاة المطلوبة،
     * ولا يستطيع اختيار صلاة أقصر للتحايل.
     */
    private fun openCoach(rakaat: Int) {
        showWeb()
        webView?.loadUrl("$NOOR_WEB#pray=$rakaat")
        Toast.makeText(this, "أدِّ الصلاة كاملة أمام الكاميرا ليُفتح القفل", Toast.LENGTH_LONG).show()
    }

    /** يفتح تحدّي الاستغفار بالعدد والذكر المحفوظين */
    private fun openChallenge() {
        showWeb()
        val reps = ChallengeLock.reps(this)
        val phrase = java.net.URLEncoder.encode(ChallengeLock.phrase(this), "UTF-8")
        webView?.loadUrl("$NOOR_WEB#challenge=$reps&phrase=$phrase")
        Toast.makeText(this, "أتمّ الضغطات مع الذكر ليُفكّ الحظر", Toast.LENGTH_LONG).show()
    }

    /**
     * يُنصت ويتحقّق من نطق الذكر.
     * التعرّف على الكلام لا يعمل داخل WebView، فنستخدم محرّك أندرويد
     * الأصلي ونُعيد النتيجة إلى الصفحة عبر window.__noorSpeech.
     */
    private var recognizer: android.speech.SpeechRecognizer? = null

    private fun listenForDhikr(phrase: String) {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            replySpeech(false)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 3)
            replySpeech(false)
            return
        }

        recognizer?.destroy()
        recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val said = results
                        ?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.joinToString(" ") ?: ""
                    replySpeech(matchesDhikr(said, phrase))
                }
                override fun onError(error: Int) = replySpeech(false)
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
        }

        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                     android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ar")
            putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        try { recognizer?.startListening(intent) } catch (e: Exception) { replySpeech(false) }
    }

    /** مقارنة متسامحة: نُجرّد التشكيل ونقبل تطابق أغلب الكلمات */
    private fun matchesDhikr(said: String, phrase: String): Boolean {
        fun strip(s: String) = s
            .replace(Regex("[\\u064B-\\u0652\\u0670\\u0640]"), "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
            .replace('ة', 'ه').replace('ى', 'ي')
            .trim()

        val heard = strip(said)
        val want = strip(phrase).split(Regex("\\s+")).filter { it.length > 2 }
        if (want.isEmpty()) return heard.isNotEmpty()
        val hits = want.count { heard.contains(it) }
        return hits >= (want.size + 1) / 2      // نصف الكلمات فأكثر
    }

    private fun replySpeech(ok: Boolean) {
        webView?.evaluateJavascript(
            "try{ window.__noorSpeech && window.__noorSpeech($ok) }catch(e){}", null
        )
    }

    override fun onResume() {
        super.onResume()
        if (blockerView != null) refreshStatus()
        ticker.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
    }

    /* ─────────── شريط الصلاة القادمة ─────────── */
    private fun buildBanner(): TextView {
        banner = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#F0D98A"))
            setBackgroundColor(Color.parseColor("#0A3227"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        updateBanner()
        return banner
    }

    private fun updateBanner() {
        if (!::banner.isInitialized) return
        val (name, at) = PrayerAlarm.nextPrayerAt(this)
        var left = (at - System.currentTimeMillis()) / 1000
        if (left < 0) left = 0
        val h = left / 3600
        val m = (left % 3600) / 60
        val s = left % 60
        val prayer = String.format("🕌 %s بعد  %02d:%02d:%02d", name, h, m, s)
        banner.text = if (ChallengeLock.enabled(this))
            "$prayer   ·   💎 ${Credit.format(this)}" else prayer
    }

    /* ─────────── التبويبان ─────────── */
    private fun buildTabs(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.parseColor("#06231C"))
        }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

        tabWeb = Button(this).apply {
            text = "الأذكار والقرآن"
            setOnClickListener { showWeb() }
        }
        tabBlock = Button(this).apply {
            text = "قفل الصلاة"
            setOnClickListener { showBlocker() }
        }
        bar.addView(tabWeb, lp)
        bar.addView(tabBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return bar
    }

    private fun markTabs(webActive: Boolean) {
        tabWeb.setBackgroundColor(if (webActive) Color.parseColor("#D4AF37") else Color.parseColor("#0A3227"))
        tabWeb.setTextColor(if (webActive) Color.parseColor("#16130A") else Color.WHITE)
        tabBlock.setBackgroundColor(if (webActive) Color.parseColor("#0A3227") else Color.parseColor("#D4AF37"))
        tabBlock.setTextColor(if (webActive) Color.WHITE else Color.parseColor("#16130A"))
    }

    /* ─────────── تبويب الأذكار والقرآن ─────────── */
    private fun showWeb() {
        markTabs(true)
        content.removeAllViews()

        if (webView == null) {
            // نطلب إذن الكاميرا مسبقاً كي يعمل مدرّب الصلاة
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            }

            webView = WebView(this).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    // لا نخزّن الصفحات: التخزين كان يخلط ملفات قديمة بجديدة
                    // بعد كل تحديث فيظهر التطبيق فارغاً بلا أذكار ولا سور
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }

                // ننظّف ما خزّنه الإصدار السابق مرة واحدة بعد كل تحديث
                val last = Prefs.lastWebClean(this@MainActivity)
                if (last != APP_VERSION) {
                    clearCache(true)
                    android.webkit.WebStorage.getInstance().deleteAllData()
                    Prefs.setLastWebClean(this@MainActivity, APP_VERSION)
                }
                webViewClient = WebViewClient()
                webChromeClient = object : WebChromeClient() {
                    /**
                     * لا نمنح الصفحة إذناً لا يملكه التطبيق نفسه.
                     *
                     * كان هذا سبب تعليق «جارٍ فتح الكاميرا»: نمنح الصفحة الإذن
                     * بينما النظام لم يمنحه للتطبيق، فيقف طلب الكاميرا عند فتح
                     * العتاد بلا نتيجة ولا خطأ — تعليق أبدي بلا رسالة.
                     * الآن: إن كان الإذن ناقصاً نرفض صراحةً (فيصل الخطأ للصفحة
                     * وتعرض رسالة مفهومة) ونطلبه من النظام في الوقت نفسه.
                     */
                    override fun onPermissionRequest(request: PermissionRequest?) {
                        if (request == null) return
                        val needed = mutableListOf<String>()

                        for (res in request.resources) {
                            val perm = when (res) {
                                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                                else -> null
                            } ?: continue
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                                checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                                needed.add(perm)
                            }
                        }

                        if (needed.isEmpty()) {
                            request.grant(request.resources)
                        } else {
                            request.deny()
                            requestPermissions(needed.toTypedArray(), REQ_MEDIA)
                            Toast.makeText(
                                this@MainActivity,
                                "اسمح بالكاميرا ثم اضغط «ابدأ» مرة أخرى",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                // جسر يسمح للمدرّب والتحدّي بفكّ القفل، وللإنصات للذكر
                addJavascriptInterface(
                    NoorBridge(this@MainActivity) { p -> listenForDhikr(p) }, "NoorApp"
                )
                loadUrl(NOOR_WEB)
            }
        }
        content.addView(webView)
    }

    /* ─────────── تبويب قفل الصلاة ─────────── */
    private fun showBlocker() {
        markTabs(false)
        content.removeAllViews()
        if (blockerView == null) blockerView = buildBlockerUi()
        content.addView(blockerView)
        refreshStatus()
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTextColor(Color.parseColor("#D4AF37"))
        setPadding(0, dp(20), 0, dp(9))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13.5f
        setTextColor(Color.parseColor("#9DBDB0"))
        setPadding(0, 0, 0, dp(8))
    }

    private fun buildBlockerUi(): ScrollView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(26))
        }

        /* حالة الخدمة */
        col.addView(header("١) تفعيل الخدمة"))
        statusView = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, dp(9))
        }
        col.addView(statusView)
        col.addView(Button(this).apply {
            text = "افتح إعدادات إمكانية الوصول"
            setOnClickListener {
                Toast.makeText(this@MainActivity,
                    "ابحث عن «نور — قفل الصلاة» وفعّله", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        /* تنبيه الأذان */
        col.addView(header("٢) تنبيه الأذان"))
        col.addView(body("ينبّهك بصوت واهتزاز عند دخول كل وقت، حتى لو كان التطبيق مغلقاً."))
        col.addView(CheckBox(this).apply {
            text = "فعّل تنبيه الأذان"
            textSize = 15.5f
            setTextColor(Color.parseColor("#EAF5F0"))
            isChecked = Prefs.notifyEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setNotifyEnabled(this@MainActivity, checked)
                PrayerAlarm.schedule(this@MainActivity)
                Toast.makeText(
                    this@MainActivity,
                    if (checked) "سيصلك التنبيه عند كل أذان" else "أُوقف تنبيه الأذان",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })

        col.addView(CheckBox(this).apply {
            text = "ارفع الأذان بصوت مؤذّن (بدل نغمة قصيرة)"
            textSize = 15.5f
            setTextColor(Color.parseColor("#EAF5F0"))
            isChecked = Prefs.adhanSound(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setAdhanSound(this@MainActivity, checked)
                if (!checked) AdhanPlayer.stop(this@MainActivity)
                Toast.makeText(
                    this@MainActivity,
                    if (checked) "سيُرفع الأذان كاملاً عند كل وقت" else "ستصلك نغمة تنبيه قصيرة",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })

        col.addView(body("الأذان يخرج على صوت المنبّه، فيُسمع حتى لو كان الهاتف صامتاً."))

        /* زرّان للتجربة والإيقاف — لا تنتظر وقت الصلاة لتعرف أنه يعمل */
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = "🔊 جرّب الأذان"
                setOnClickListener { AdhanPlayer.play(this@MainActivity, "التجربة") }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Button(this@MainActivity).apply {
                text = "■ أوقف"
                setOnClickListener { AdhanPlayer.stop(this@MainActivity) }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        })

        /* أوقات اليوم */
        col.addView(header("٣) أوقات الصلاة اليوم"))
        timesView = TextView(this).apply {
            textSize = 14.5f
            setTextColor(Color.parseColor("#EAF5F0"))
            setLineSpacing(0f, 1.45f)
        }
        col.addView(timesView)

        /* مدة القفل */
        col.addView(header("٤) مدة قفل الهاتف بعد الأذان"))
        col.addView(body(
            "عند دخول الوقت يُقفل الهاتف بالكامل — كل التطبيقات والشاشة " +
            "الرئيسية — ولا يوجد زرّ لفكّه. يُفتح وحده عند انتهاء المدة.\n" +
            "الاتصال والطوارئ والإعدادات تبقى متاحة دائماً."
        ))
        val durLabel = body("${Prefs.lockMinutes(this)} دقيقة")
        col.addView(durLabel)
        col.addView(android.widget.SeekBar(this).apply {
            max = 5
            progress = (Prefs.lockMinutes(this@MainActivity) - 5) / 5
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    val m = 5 + p * 5
                    durLabel.text = "$m دقيقة"
                    Prefs.setLockMinutes(this@MainActivity, m)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        })

        /* نظام النقاط */
        col.addView(header("٥) نظام النقاط — اكسب وقتك"))
        col.addView(body("الضغطات مع الذكر تشتري لك دقائق استخدام."))
        col.addView(body("والدقائق لا تُصرف إلا وأنت داخل التطبيقات المحظورة: تخرج من إنستغرام فيتوقّف العدّاد ويبقى رصيدك كما هو، وتعود فيُستأنف."))
        col.addView(body("ينفد الرصيد فتُقفل حتى تكسب غيره."))

        val chState = body("")
        col.addView(CheckBox(this).apply {
            text = "فعّل نظام النقاط"
            textSize = 15.5f
            setTextColor(Color.parseColor("#EAF5F0"))
            isChecked = ChallengeLock.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                ChallengeLock.setEnabled(this@MainActivity, on)
                renderChallengeState(chState)
                Toast.makeText(this@MainActivity,
                    if (on) "فُعّل — اكسب رصيداً لتستخدم التطبيقات المحظورة"
                    else "أُوقف نظام النقاط", Toast.LENGTH_SHORT).show()
            }
        })
        col.addView(chState)

        col.addView(body("كم دقيقة تكسبها كل ضغطة؟"))
        val perRepLabel = body("${Credit.minutesPerRep(this)} دقيقة لكل ضغطة")
        col.addView(perRepLabel)
        col.addView(android.widget.SeekBar(this).apply {
            max = 4   // ١ ٢ ٣ ٥ ١٠
            val steps = intArrayOf(1, 2, 3, 5, 10)
            progress = steps.indexOf(Credit.minutesPerRep(this@MainActivity)).coerceAtLeast(1)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, u: Boolean) {
                    Credit.setMinutesPerRep(this@MainActivity, steps[p])
                    perRepLabel.text = "${steps[p]} دقيقة لكل ضغطة"
                    renderChallengeState(chState)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        })

        col.addView(body("عدد الضغطات المطلوبة"))
        val repsLabel = body("${ChallengeLock.reps(this)} ضغطة")
        col.addView(repsLabel)
        col.addView(android.widget.SeekBar(this).apply {
            max = 5   // ٥ ١٠ ١٥ ٢٠ ٣٠ ٥٠
            val steps = intArrayOf(5, 10, 15, 20, 30, 50)
            progress = steps.indexOf(ChallengeLock.reps(this@MainActivity)).coerceAtLeast(1)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, u: Boolean) {
                    repsLabel.text = "${steps[p]} ضغطة"
                    ChallengeLock.setReps(this@MainActivity, steps[p])
                    renderChallengeState(chState)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        })

        col.addView(body("الذكر الذي تقوله مع كل ضغطة"))
        val phrases = listOf(
            "أَسْتَغْفِرُ اللهَ", "الْحَمْدُ لِلّٰهِ", "اللهُ أَكْبَرُ",
            "سُبْحَانَ اللهِ", "لَا إِلٰهَ إِلَّا اللهُ"
        )
        val phraseBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun paintPhrases() {
            phraseBox.removeAllViews()
            for (ph in phrases) {
                val chosen = ChallengeLock.phrase(this@MainActivity) == ph
                phraseBox.addView(Button(this@MainActivity).apply {
                    text = ph
                    textSize = 15f
                    setBackgroundColor(Color.parseColor(if (chosen) "#D4AF37" else "#0A3227"))
                    setTextColor(Color.parseColor(if (chosen) "#16130A" else "#EAF5F0"))
                    setOnClickListener {
                        ChallengeLock.setPhrase(this@MainActivity, ph)
                        paintPhrases()
                    }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) })
            }
        }
        paintPhrases()
        col.addView(phraseBox)

        col.addView(Button(this).apply {
            text = "💪 جرّب التحدّي الآن"
            setOnClickListener { openChallenge() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        renderChallengeState(chState)

        /* اختيار التطبيقات */
        col.addView(header("٦) تطبيقات تحدّي الاستغفار"))
        col.addView(body(
            "هذه القائمة لتحدّي الاستغفار وحده.\n" +
            "قفل الصلاة لم يعد يعتمد عليها — فهو يقفل الهاتف كلّه."
        ))

        countView = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(4), 0, dp(10))
        }
        col.addView(countView)

        /* أزرار سريعة */
        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val qlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        quick.addView(Button(this).apply {
            text = "اختر الشائعة"
            setOnClickListener {
                val installed = allApps.map { it.packageName }.toSet()
                var added = 0
                for (p in COMMON_DISTRACTIONS) if (p in installed && selected.add(p)) added++
                save(quiet = true)
                renderApps()
                Toast.makeText(this@MainActivity, "أُضيف $added تطبيقاً", Toast.LENGTH_SHORT).show()
            }
        }, qlp)
        quick.addView(Button(this).apply {
            text = "امسح الكل"
            setOnClickListener {
                selected.clear()
                save(quiet = true)
                renderApps()
                Toast.makeText(this@MainActivity, "أُلغي حظر كل التطبيقات", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(quick)

        /* بحث */
        col.addView(EditText(this).apply {
            hint = "ابحث عن تطبيق…"
            textSize = 15f
            setTextColor(Color.parseColor("#EAF5F0"))
            setHintTextColor(Color.parseColor("#6E8F82"))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    filter = s?.toString()?.trim() ?: ""
                    renderApps()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })

        appsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(appsBox)

        loadApps()
        renderApps()

        /* زر تأكيد واضح — الحفظ فوري لكن المستخدم يحتاج أن يرى ذلك */
        col.addView(Button(this).apply {
            text = "✓ تم — احفظ وارجع"
            textSize = 17f
            setBackgroundColor(Color.parseColor("#D4AF37"))
            setTextColor(Color.parseColor("#16130A"))
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener {
                Prefs.setBlockedApps(this@MainActivity, selected)
                Toast.makeText(
                    this@MainActivity,
                    "✅ تم الحفظ — ${selected.size} تطبيق سيُمنع وقت الصلاة",
                    Toast.LENGTH_LONG
                ).show()
                showWeb()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })

        col.addView(TextView(this).apply {
            text = "\nكل تغيير يُحفظ فوراً — الزر للتأكيد والرجوع فقط.\n\n" +
                   "ملاحظة: لا يقرأ التطبيق محتوى شاشتك إطلاقاً — يعرف فقط اسم التطبيق المفتوح."
            textSize = 12f
            setTextColor(Color.parseColor("#6E8F82"))
            setPadding(0, dp(14), 0, 0)
        })

        return ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#04150F"))
            addView(col)
        }
    }

    private fun loadApps() {
        val pm = packageManager
        allApps = pm.getInstalledApplications(0)
            .filter { it.packageName != packageName }
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || selected.contains(it.packageName) }
            .sortedBy { pm.getApplicationLabel(it).toString() }
    }

    /** يحدّث العدّاد ويُظهر أن الاختيار محفوظ */
    private fun updateCount(justSaved: Boolean = false) {
        val n = selected.size
        countView.text = when {
            n == 0 -> "لم تختر أي تطبيق بعد"
            justSaved -> "✅ محفوظ — $n تطبيق محظور وقت الصلاة"
            else -> "$n تطبيق محظور وقت الصلاة"
        }
        countView.setTextColor(
            when {
                n == 0 -> Color.parseColor("#9DBDB0")
                n > 15 -> Color.parseColor("#EF4444")
                else -> Color.parseColor("#10B981")
            }
        )
    }

    private fun renderApps() {
        val pm = packageManager
        appsBox.removeAllViews()
        updateCount()

        val list = allApps.filter {
            filter.isEmpty() ||
            pm.getApplicationLabel(it).toString().contains(filter, ignoreCase = true)
        }

        if (list.isEmpty()) {
            appsBox.addView(body("لا يوجد تطبيق بهذا الاسم."))
            return
        }

        // المحظورة أولاً كي يراها بوضوح
        val ordered = list.sortedByDescending { selected.contains(it.packageName) }

        for (app in ordered) {
            val pkg = app.packageName
            appsBox.addView(CheckBox(this).apply {
                text = pm.getApplicationLabel(app).toString()
                textSize = 15f
                setTextColor(
                    if (selected.contains(pkg)) Color.parseColor("#F0D98A") else Color.parseColor("#EAF5F0")
                )
                isChecked = selected.contains(pkg)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(pkg) else selected.remove(pkg)
                    save(quiet = true)
                    updateCount(justSaved = true)
                    setTextColor(
                        if (checked) Color.parseColor("#F0D98A") else Color.parseColor("#EAF5F0")
                    )
                }
            })
        }
    }

    /** يعرض رصيدك الآن وقيمة الجولة القادمة */
    private fun renderChallengeState(v: TextView) {
        if (!ChallengeLock.enabled(this)) {
            v.text = "نظام النقاط متوقّف"
            v.setTextColor(Color.parseColor("#6E8F82"))
            return
        }
        val reward = "الجولة: ${ChallengeLock.reps(this)} ضغطة = ${Credit.rewardMinutes(this)} دقيقة"
        if (Credit.isEmpty(this)) {
            v.text = "🔒 رصيدك صفر — أتمّ جولة لتكسب\n$reward"
            v.setTextColor(Color.parseColor("#EF4444"))
        } else {
            val flow = if (Credit.isSpending(this)) "⏳ يُصرف الآن" else "⏸ متوقّف — لست في تطبيق محظور"
            v.text = "💎 رصيدك ${Credit.format(this)}  ·  $flow\n$reward"
            v.setTextColor(Color.parseColor("#10B981"))
        }
    }

    private fun save(quiet: Boolean = false) {
        Prefs.setBlockedApps(this, selected)
        if (!quiet) Toast.makeText(this, "تم الحفظ", Toast.LENGTH_SHORT).show()
    }

    private fun refreshStatus() {
        val on = isServiceEnabled()
        statusView.text = if (on) "✅ الخدمة مفعّلة — الحظر يعمل" else "⛔ الخدمة غير مفعّلة"
        statusView.setTextColor(Color.parseColor(if (on) "#10B981" else "#EF4444"))

        val t = PrayerLock.todayTimes(this)
        val sb = StringBuilder()
        for (i in t.indices) {
            sb.append(PrayerLock.NAMES[i]).append("   ").append(PrayerTimes.format(t[i]))
            if (i < t.size - 1) sb.append("\n")
        }
        val next = PrayerLock.next(this)
        sb.append("\n\nالقادمة: ").append(next.first).append(" في ").append(next.second)
        sb.append("\nالموقع: ").append(Prefs.cityName(this))
        timesView.text = sb.toString()
    }

    private fun isServiceEnabled(): Boolean {
        val expected = "$packageName/${BlockerService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    /** زر الرجوع يتنقّل داخل صفحات الأذكار والقرآن */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = webView
        if (w != null && w.parent != null && w.canGoBack()) w.goBack()
        else @Suppress("DEPRECATION") super.onBackPressed()
    }
}
