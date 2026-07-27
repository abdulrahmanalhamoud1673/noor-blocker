package com.noor.blocker

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var timesView: TextView
    private val selected = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected.addAll(Prefs.blockedApps(this))
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTextColor(Color.parseColor("#D4AF37"))
        setPadding(0, dp(22), 0, dp(10))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.parseColor("#9DBDB0"))
        setPadding(0, 0, 0, dp(8))
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#04150F"))
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        root.addView(TextView(this).apply {
            text = "نور — قفل الصلاة"
            textSize = 25f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })

        // ── حالة الخدمة ──
        root.addView(header("١) تفعيل الخدمة"))
        statusView = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(statusView)

        root.addView(Button(this).apply {
            text = "افتح إعدادات إمكانية الوصول"
            setOnClickListener {
                Toast.makeText(
                    this@MainActivity,
                    "ابحث عن «نور — قفل الصلاة» وفعّله",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        // ── أوقات اليوم ──
        root.addView(header("٢) أوقات الصلاة اليوم"))
        timesView = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#EAF5F0"))
            setLineSpacing(0f, 1.5f)
        }
        root.addView(timesView)

        // ── مدة القفل ──
        root.addView(header("٣) مدة القفل بعد الأذان"))
        val durLabel = body("${Prefs.lockMinutes(this)} دقيقة")
        root.addView(durLabel)
        root.addView(SeekBar(this).apply {
            max = 5
            progress = (Prefs.lockMinutes(this@MainActivity) - 5) / 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val minutes = 5 + p * 5
                    durLabel.text = "$minutes دقيقة"
                    Prefs.setLockMinutes(this@MainActivity, minutes)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        // ── قائمة التطبيقات ──
        root.addView(header("٤) التطبيقات التي أريد حظرها"))
        root.addView(body("علّم على التطبيقات التي تشتّتك. عند دخول وقت الصلاة لن تفتح حتى تصلّي."))

        val appsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(appsBox)
        loadApps(appsBox)

        root.addView(Button(this).apply {
            text = "احفظ الاختيار"
            setOnClickListener {
                Prefs.setBlockedApps(this@MainActivity, selected)
                Toast.makeText(
                    this@MainActivity,
                    "تم حفظ ${selected.size} تطبيق",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })

        root.addView(TextView(this).apply {
            text = "\nملاحظة: لا يقرأ التطبيق محتوى شاشتك إطلاقاً — " +
                   "يعرف فقط اسم التطبيق المفتوح ليقارنه بقائمتك."
            textSize = 12f
            setTextColor(Color.parseColor("#6E8F82"))
            setPadding(0, dp(16), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#04150F"))
            addView(root)
        })
    }

    private fun loadApps(container: LinearLayout) {
        val pm = packageManager
        val launchables = pm.getInstalledApplications(0)
            .filter { it.packageName != packageName }
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || selected.contains(it.packageName) }
            .sortedBy { pm.getApplicationLabel(it).toString() }

        if (launchables.isEmpty()) {
            container.addView(body("لم يُعثر على تطبيقات."))
            return
        }

        for (app in launchables) {
            val pkg = app.packageName
            container.addView(CheckBox(this).apply {
                text = pm.getApplicationLabel(app).toString()
                textSize = 15f
                setTextColor(Color.parseColor("#EAF5F0"))
                isChecked = selected.contains(pkg)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(pkg) else selected.remove(pkg)
                }
            })
        }
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

    /** هل خدمة إمكانية الوصول مفعّلة؟ */
    private fun isServiceEnabled(): Boolean {
        val expected = "$packageName/${BlockerService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}
