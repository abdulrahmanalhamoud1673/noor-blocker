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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected.addAll(Prefs.blockedApps(this))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#04150F"))
        }

        root.addView(buildTabs())

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
        showWeb()
    }

    override fun onResume() {
        super.onResume()
        if (blockerView != null) refreshStatus()
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
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
                webViewClient = WebViewClient()
                webChromeClient = object : WebChromeClient() {
                    // نمنح الصفحة إذن الكاميرا (المستخدم منحه للتطبيق أصلاً)
                    override fun onPermissionRequest(request: PermissionRequest?) {
                        request?.grant(request.resources)
                    }
                }
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

        /* أوقات اليوم */
        col.addView(header("٢) أوقات الصلاة اليوم"))
        timesView = TextView(this).apply {
            textSize = 14.5f
            setTextColor(Color.parseColor("#EAF5F0"))
            setLineSpacing(0f, 1.45f)
        }
        col.addView(timesView)

        /* مدة القفل */
        col.addView(header("٣) مدة القفل بعد الأذان"))
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

        /* اختيار التطبيقات */
        col.addView(header("٤) التطبيقات التي أريد حظرها"))
        col.addView(body("اختر ما يشتّتك فقط — لا تحظر كل شيء وإلا تعطّل هاتفك وقت الصلاة."))

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

        col.addView(TextView(this).apply {
            text = "\nالاختيار يُحفظ فور الضغط — لا حاجة لزر حفظ.\n\n" +
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

    private fun renderApps() {
        val pm = packageManager
        appsBox.removeAllViews()
        countView.text = "المحظورة الآن: ${selected.size} تطبيق"
        countView.setTextColor(
            if (selected.size > 15) Color.parseColor("#EF4444") else Color.parseColor("#10B981")
        )

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
                    countView.text = "المحظورة الآن: ${selected.size} تطبيق"
                    countView.setTextColor(
                        if (selected.size > 15) Color.parseColor("#EF4444") else Color.parseColor("#10B981")
                    )
                    setTextColor(
                        if (checked) Color.parseColor("#F0D98A") else Color.parseColor("#EAF5F0")
                    )
                }
            })
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
