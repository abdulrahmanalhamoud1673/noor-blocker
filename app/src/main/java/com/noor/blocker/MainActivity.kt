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
import android.view.View
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
const val NOOR_WEB = "https://abdulrahmanalhamoud1673.github.io/noor-adhkar/"

/** يُستخدم لمسح ذاكرة الويب مرة واحدة بعد كل تحديث */
private const val APP_VERSION = "16.0"

/** رمز طلب أذونات الوسائط (كاميرا / ميكروفون) من داخل الصفحة */
private const val REQ_MEDIA = 11

/**
 * جسر بين صفحة الأذكار ومحرّك التطبيق.
 * لم يبقَ منه إلا إضافة الرصيد عند إتمام تحدٍّ من صفحة الويب.
 */
class NoorBridge(private val ctx: android.content.Context) {
    @android.webkit.JavascriptInterface
    fun challengeCompleted() {
        val mins = Credit.award(ctx)
        val total = Credit.format(ctx)
        EventLog.add(ctx, EventLog.EARN,
            "أتممتَ ${ChallengeLock.reps(ctx)} ضغطة — كسبتَ $mins دقيقة (الرصيد $total)")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(ctx, "أحسنت — كسبتَ $mins دقيقة · رصيدك $total", Toast.LENGTH_LONG).show()
        }
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
    private lateinit var appsBox: LinearLayout
    private lateinit var suggestBox: LinearLayout

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
            setBackgroundColor(Color.parseColor("#EFE9DD"))
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
        if (intent?.getBooleanExtra(LockActivity.EXTRA_OPEN_CHALLENGE, false) == true) {
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

    /** يفتح تحدّي الاستغفار بالعدد والذكر المحفوظين */
    /**
     * التحدّي صار شاشةً أصيلة لا صفحةَ ويب: حسّاس القرب يعدّ الضغطات
     * والميكروفون يبقى مفتوحاً طوال الجولة. الكاميرا لم تكن ترى الحركة.
     */
    private fun openChallenge() {
        startActivity(Intent(this, ChallengeActivity::class.java))
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
            setTextColor(Color.parseColor("#8D5F33"))
            setBackgroundColor(Color.parseColor("#FFFFFF"))
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
            setBackgroundColor(Color.parseColor("#F7F3EC"))
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

    /** 0 = الأذكار · 1 = قفل الصلاة */
    private fun markTabs(which: Int) {
        // نصّ التبويب المُطفأ كان أبيض على أبيض — غير مرئيّ
        val on = Color.parseColor("#8E5C2C"); val off = Color.parseColor("#FFFFFF")
        val onT = Color.parseColor("#FFFFFF"); val offT = Color.parseColor("#2B2A31")
        listOf(tabWeb, tabBlock).forEachIndexed { i, b ->
            b.setBackgroundColor(if (i == which) on else off)
            b.setTextColor(if (i == which) onT else offT)
            b.textSize = 13f
        }
    }

    /* ─────────── تبويب الأذكار والقرآن ─────────── */
    private fun showWeb() {
        markTabs(0)
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
                addJavascriptInterface(NoorBridge(this@MainActivity), "NoorApp")
                loadUrl(NOOR_WEB)
            }
        }
        content.addView(webView)
    }

    /* ─────────── تبويب قفل الصلاة ─────────── */
    private fun showBlocker() {
        markTabs(1)
        content.removeAllViews()
        if (blockerView == null) blockerView = buildBlockerUi()
        content.addView(blockerView)
        refreshStatus()
    }
    /** يقول بصراحة أي إذن ناقص — فهذان ما يُسكتان الأذان غالباً */
    private fun paintReady(v: TextView) {
        val exact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
            am.canScheduleExactAlarms()
        } else true
        val batt = try {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (e: Exception) { true }

        v.text = (if (exact) "✅ المنبّهات الدقيقة: مسموحة" else "❌ المنبّهات الدقيقة: ممنوعة") +
            "\n" + (if (batt) "✅ البطارية: نور مستثنى" else "❌ البطارية: نور غير مستثنى")
        v.setTextColor(android.graphics.Color.parseColor(
            if (exact && batt) "#4A72A8" else "#C0564E"))
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTextColor(Color.parseColor("#B07A46"))
        setPadding(0, dp(20), 0, dp(9))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13.5f
        setTextColor(Color.parseColor("#7B7468"))
        setPadding(0, 0, 0, dp(8))
    }

    /* ─────────── قطع التصميم ─────────── */

    /** بطاقة بيضاء بحوافّ ناعمة — الوحدة البصرية الوحيدة في الشاشة */
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(16))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.parseColor("#FFFFFF"))
            setStroke(dp(1), Color.parseColor("#E6DFD2"))
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
    }

    private fun cardTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 16.5f
        setTextColor(Color.parseColor("#2B2A31"))
        setPadding(0, 0, 0, dp(10))
    }

    /** سطر: عنوان يمين، قيمة أو مفتاح يسار */
    private fun row(label: String, right: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(7), 0, dp(7))
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 15f
            setTextColor(Color.parseColor("#2B2A31"))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(right)
    }

    private fun toggle(on: Boolean, change: (Boolean) -> Unit) = android.widget.Switch(this).apply {
        isChecked = on
        setOnCheckedChangeListener { _, v -> change(v) }
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t
        textSize = 12.5f
        setTextColor(Color.parseColor("#8A8275"))
        setPadding(0, dp(2), 0, 0)
    }

    /** عدّاد ‏–‏ ‏+‏ بدل المسطرة: أدقّ بالإصبع وأقصر على الشاشة */
    private fun stepper(value: Int, min: Int, max: Int, step: Int,
                        unit: String, change: (Int) -> Unit): LinearLayout {
        var v = value
        val out = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#8E5C2C"))
            gravity = Gravity.CENTER
            minWidth = dp(92)
        }
        fun paint() { out.text = "$v $unit" }
        paint()

        fun btn(sign: String, delta: Int) = TextView(this).apply {
            text = sign
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#2B2A31"))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#F3EEE4"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(34))
            setOnClickListener {
                v = (v + delta).coerceIn(min, max)
                paint(); change(v)
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(btn("−", -step))
            addView(out)
            addView(btn("+", step))
        }
    }

    /* ─────────── الشاشة ─────────── */
    private fun buildBlockerUi(): ScrollView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(26))
        }

        /* ١ — الحالة */
        val c1 = card()
        statusView = TextView(this).apply { textSize = 15f }
        c1.addView(statusView)
        c1.addView(Button(this).apply {
            text = "فتح إعدادات إمكانية الوصول"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this@MainActivity, "ابحث عن «نور» وفعّله", Toast.LENGTH_LONG).show()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        })
        val ready = hint("")
        paintReady(ready)
        c1.addView(ready)
        c1.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = "المنبّهات"
                textSize = 13f
                setOnClickListener {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        try {
                            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                android.net.Uri.parse("package:" + packageName)))
                        } catch (e: Exception) {}
                    }
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Button(this@MainActivity).apply {
                text = "البطارية"
                textSize = 13f
                setOnClickListener {
                    try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    catch (e: Exception) {}
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        })
        col.addView(c1)

        /* ٢ — الأذان والقفل */
        val c2 = card()
        c2.addView(cardTitle("🕌 وقت الصلاة"))
        c2.addView(row("تنبيه الأذان", toggle(Prefs.notifyEnabled(this)) {
            Prefs.setNotifyEnabled(this, it); PrayerAlarm.schedule(this)
        }))
        c2.addView(row("صوت مؤذّن", toggle(Prefs.adhanSound(this)) {
            Prefs.setAdhanSound(this, it); if (!it) AdhanPlayer.stop(this)
        }))
        c2.addView(row("اقفل الهاتف", toggle(Prefs.enabled(this)) { Prefs.setEnabled(this, it) }))
        c2.addView(row("المدة", stepper(Prefs.lockMinutes(this), 5, 45, 5, "دقيقة") {
            Prefs.setLockMinutes(this, it)
        }))
        val lockState = hint("")
        fun paintLock() {
            lockState.text = if (ScreenLock.isAdmin(this))
                "✅ يُقفل الهاتف نفسه" else "الشاشة لن تُقفل — امنح الصلاحية"
            lockState.setTextColor(Color.parseColor(
                if (ScreenLock.isAdmin(this)) "#4A72A8" else "#C0564E"))
        }
        paintLock()
        c2.addView(lockState)
        c2.addView(Button(this).apply {
            text = if (ScreenLock.isAdmin(this@MainActivity)) "إلغاء صلاحية القفل" else "امنح صلاحية القفل"
            textSize = 13f
            setOnClickListener {
                if (ScreenLock.isAdmin(this@MainActivity)) {
                    ScreenLock.revoke(this@MainActivity); paintLock()
                    text = "امنح صلاحية القفل"
                } else startActivityForResult(ScreenLock.requestIntent(this@MainActivity), 77)
            }
        })
        c2.addView(timesView.apply {
            textSize = 13f
            setTextColor(Color.parseColor("#8A8275"))
            setPadding(0, dp(10), 0, 0)
        })
        col.addView(c2)

        /* ٣ — تحدّي الاستغفار */
        val c3 = card()
        c3.addView(cardTitle("💪 تحدّي الاستغفار"))
        c3.addView(row("تفعيل", toggle(ChallengeLock.enabled(this)) {
            ChallengeLock.setEnabled(this, it)
        }))
        c3.addView(row("كل", stepper(ChallengeLock.intervalMin(this), 15, 240, 15, "دقيقة") {
            ChallengeLock.setIntervalMin(this, it)
        }))
        c3.addView(row("الضغطات", stepper(ChallengeLock.reps(this), 5, 50, 5, "ضغطة") {
            ChallengeLock.setReps(this, it)
        }))
        c3.addView(row("تكسب", stepper(Credit.minutesPerRep(this), 1, 10, 1, "د/ضغطة") {
            Credit.setMinutesPerRep(this, it)
        }))
        c3.addView(hint("ضع الهاتف تحت صدرك — حسّاس القرب يعدّ."))
        c3.addView(Button(this).apply {
            text = "جرّب التحدّي"
            textSize = 13f
            setOnClickListener { startActivity(Intent(this@MainActivity, ChallengeActivity::class.java)) }
        })
        col.addView(c3)

        /* ٤ — التطبيقات */
        val c4 = card()
        c4.addView(cardTitle("📵 التطبيقات المحظورة"))
        val search = EditText(this).apply {
            hint = "اكتب اسم التطبيق…"
            textSize = 15f
            setTextColor(Color.parseColor("#2B2A31"))
            setHintTextColor(Color.parseColor("#A79F91"))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#F7F3EC"))
                setStroke(dp(1), Color.parseColor("#E6DFD2"))
            }
            setPadding(dp(12), dp(11), dp(12), dp(11))
        }
        c4.addView(search)
        suggestBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c4.addView(suggestBox)
        appsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        c4.addView(appsBox)
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                filter = s?.toString()?.trim() ?: ""
                renderSuggestions()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        loadApps()
        renderChosen()
        col.addView(c4)

        return ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F3EEE4"))
            addView(col)
        }
    }

    /** مطابقات ما يكتبه — خمسة على الأكثر، لا قائمة الهاتف كلّها */
    private fun renderSuggestions() {
        if (!::suggestBox.isInitialized) return
        suggestBox.removeAllViews()
        val q = filter.lowercase()
        if (q.length < 2) return
        val pm = packageManager
        allApps.asSequence()
            .filter { !selected.contains(it.packageName) }
            .filter { pm.getApplicationLabel(it).toString().lowercase().contains(q) }
            .take(5)
            .forEach { app ->
                suggestBox.addView(TextView(this).apply {
                    text = "＋  " + pm.getApplicationLabel(app)
                    textSize = 14.5f
                    setTextColor(Color.parseColor("#4A72A8"))
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    setOnClickListener {
                        selected.add(app.packageName)
                        Prefs.setBlockedApps(this@MainActivity, selected)
                        filter = ""
                        suggestBox.removeAllViews()
                        renderChosen()
                    }
                })
            }
    }
    /**
     * نسأل عمّا يُفتح بأيقونة لا عن كل ما هو مثبّت.
     *
     * getInstalledApplications يحتاج QUERY_ALL_PACKAGES، وجوجل بلاي
     * تقصره على أنواعٍ لسنا منها. وسؤال «أرني ما له أيقونة» يعطي
     * القائمة نفسها — فقد كنّا نُسقط ما عداها أصلاً.
     */
    private fun loadApps() {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        allApps = pm.queryIntentActivities(launcher, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != packageName }
            .sortedBy { pm.getApplicationLabel(it).toString() }
    }

    /** ما اخترته وحده — قائمةٌ قصيرة بإمكان حذف كلٍّ منها */
    private fun renderChosen() {
        if (!::appsBox.isInitialized) return
        appsBox.removeAllViews()
        val pm = packageManager
        if (selected.isEmpty()) {
            appsBox.addView(hint("لم تختر شيئاً بعد."))
            return
        }
        selected.sorted().forEach { pkg ->
            val name = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) { pkg }
            appsBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(9), dp(10), dp(9))
                addView(TextView(this@MainActivity).apply {
                    text = name
                    textSize = 15f
                    setTextColor(Color.parseColor("#2B2A31"))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@MainActivity).apply {
                    text = "✕"
                    textSize = 15f
                    setTextColor(Color.parseColor("#C0564E"))
                    setPadding(dp(8), 0, dp(8), 0)
                    setOnClickListener {
                        selected.remove(pkg)
                        Prefs.setBlockedApps(this@MainActivity, selected)
                        renderChosen()
                    }
                })
            })
        }
    }

    private fun refreshStatus() {
        val on = isServiceEnabled()
        statusView.text = if (on) "✅ الخدمة مفعّلة — الحظر يعمل" else "⛔ الخدمة غير مفعّلة"
        statusView.setTextColor(Color.parseColor(if (on) "#4A72A8" else "#C0564E"))

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
