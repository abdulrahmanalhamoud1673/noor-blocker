package com.noor.blocker

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * لوحة التحكّم — ترى كل ما يجري وتتحكّم فيه.
 *
 * ثلاثة أقسام:
 *  ١) الحالة الآن: رصيدك، هل يُصرف، قفل الصلاة، حالة الخدمة.
 *  ٢) الأرقام: ما كسبتَ وما صرفتَ، واليوم، وتوزيعه على التطبيقات.
 *  ٣) السجلّ: ماذا جرى ومتى، بما فيه ما فعلتَه أنت.
 *
 * ملاحظة مقصودة: منحُ الرصيد لنفسك متاح — فهذا هاتفك — لكنه يُسجَّل
 * في السجلّ مثل أي حدث آخر. نظام انضباط يُخفي التفافك عليه لا يفيدك.
 */
class AdminPanel(private val act: Activity) {

    private val d = act.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private lateinit var statusBox: TextView
    private lateinit var statsBox: TextView
    private lateinit var appsBox: TextView
    private lateinit var logBox: LinearLayout

    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            renderStatus()
            ticker.postDelayed(this, 1000)
        }
    }

    fun startTicking() { ticker.removeCallbacks(tick); ticker.post(tick) }
    fun stopTicking() = ticker.removeCallbacks(tick)

    private fun title(t: String) = TextView(act).apply {
        text = t; textSize = 17f
        setTextColor(Color.parseColor("#D4AF37"))
        setPadding(0, dp(20), 0, dp(9))
    }

    private fun card() = TextView(act).apply {
        textSize = 14f
        setTextColor(Color.parseColor("#EAF5F0"))
        setLineSpacing(0f, 1.55f)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        setBackgroundColor(Color.parseColor("#0A3227"))
    }

    private fun action(label: String, color: String, onTap: () -> Unit) = Button(act).apply {
        text = label
        textSize = 13.5f
        setBackgroundColor(Color.parseColor(color))
        setTextColor(Color.parseColor("#16130A"))
        setOnClickListener { onTap() }
    }

    fun build(): ScrollView {
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(28))
        }
        val wide = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }

        /* ── الحالة الآن ── */
        col.addView(title("الحالة الآن"))
        statusBox = card()
        col.addView(statusBox, wide)

        /* ── الأرقام ── */
        col.addView(title("الأرقام"))
        statsBox = card()
        col.addView(statsBox, wide)

        col.addView(title("الوقت على كل تطبيق"))
        appsBox = card()
        col.addView(appsBox, wide)

        /* ── التحكّم ── */
        col.addView(title("التحكّم"))

        val row1 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val cell = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        row1.addView(action("+ ١٥ دقيقة", "#10B981") { grant(15) }, cell())
        row1.addView(action("+ ٦٠ دقيقة", "#10B981") { grant(60) }, cell())
        col.addView(row1, wide)

        val row2 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(action("صفّر الرصيد", "#D4AF37") {
            confirm("تصفير الرصيد", "سيصير رصيدك صفراً وتُقفل التطبيقات فوراً.") {
                Credit.grant(act, -99999)
                EventLog.add(act, EventLog.ADMIN, "صفّرتَ رصيدك يدوياً")
                renderAll(); toast("صُفّر الرصيد")
            }
        }, cell())
        row2.addView(action("امسح السجلّ", "#D4AF37") {
            confirm("مسح السجلّ", "سيُحذف سجلّ الأحداث. الأرقام تبقى.") {
                EventLog.clear(act); renderAll(); toast("مُسح السجلّ")
            }
        }, cell())
        col.addView(row2, wide)

        col.addView(action("↺ تصفير كل الإحصاءات", "#EF4444") {
            confirm("تصفير كل شيء",
                "سيُحذف الرصيد وكل الأرقام وسجلّ التطبيقات والأحداث. لا رجعة.") {
                Credit.resetAll(act); EventLog.clear(act)
                EventLog.add(act, EventLog.ADMIN, "صفّرتَ كل الإحصاءات")
                renderAll(); toast("صُفّر كل شيء")
            }
        }, wide)

        /* ── السجلّ ── */
        col.addView(title("سجلّ الأحداث"))
        col.addView(TextView(act).apply {
            text = "آخر ٣٠٠ حدث — الأحدث أولاً"
            textSize = 12.5f
            setTextColor(Color.parseColor("#6E8F82"))
            setPadding(0, 0, 0, dp(8))
        })
        logBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(logBox, wide)

        renderAll()
        return ScrollView(act).apply {
            setBackgroundColor(Color.parseColor("#04150F"))
            addView(col)
        }
    }

    private fun grant(min: Int) {
        Credit.grant(act, min)
        EventLog.add(act, EventLog.ADMIN, "منحتَ نفسك $min دقيقة يدوياً")
        renderAll()
        toast("أُضيفت $min دقيقة · الرصيد ${Credit.format(act)}")
    }

    private fun confirm(title: String, msg: String, onYes: () -> Unit) {
        AlertDialog.Builder(act)
            .setTitle(title).setMessage(msg)
            .setPositiveButton("نعم") { _, _ -> onYes() }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun toast(t: String) = Toast.makeText(act, t, Toast.LENGTH_SHORT).show()

    fun renderAll() { renderStatus(); renderStats(); renderApps(); renderLog() }

    private fun renderStatus() {
        if (!::statusBox.isInitialized) return
        val sb = StringBuilder()

        val lock = PrayerLock.current(act)
        if (lock != null) {
            val s = lock.secondsLeft
            sb.append("🕌 قفل الصلاة فعّال — ${lock.prayerName}")
            sb.append(String.format("%n   يُفتح بعد %02d:%02d", s / 60, s % 60))
        } else {
            val (name, at) = PrayerAlarm.nextPrayerAt(act)
            var left = (at - System.currentTimeMillis()) / 1000
            if (left < 0) left = 0
            sb.append(String.format("🕌 %s بعد %02d:%02d:%02d",
                name, left / 3600, (left % 3600) / 60, left % 60))
        }

        sb.append(String.format("%n%n💎 الرصيد: ${Credit.format(act)}"))
        sb.append(String.format("%n"))
        sb.append(when {
            !ChallengeLock.enabled(act) -> "   نظام النقاط متوقّف"
            Credit.isSpending(act) -> "   ⏳ يُصرف الآن على ${label(Credit.spendingPkg(act))}"
            Credit.isEmpty(act) -> "   🔒 صفر — التطبيقات محظورة"
            else -> "   ⏸ متوقّف — لستَ في تطبيق محظور"
        })

        sb.append(String.format("%n%n⚙ الخدمة: %s",
            if (isServiceOn()) "تعمل ✅" else "متوقّفة ❌ (فعّلها من الإعدادات)"))
        sb.append(String.format("%n🔇 الأذان: %s",
            if (Prefs.adhanSound(act)) "بصوت مؤذّن" else "نغمة قصيرة"))
        sb.append(String.format("%n📵 تطبيقات محظورة: %d", Prefs.blockedApps(act).size))

        statusBox.text = sb.toString()
    }

    private fun renderStats() {
        if (!::statsBox.isInitialized) return
        statsBox.text = StringBuilder()
            .append("كسبتَ إجمالاً: ${Credit.earnedMinutes(act)} دقيقة")
            .append(String.format("%nصرفتَ إجمالاً: ${Credit.spentMinutes(act)} دقيقة"))
            .append(String.format("%nصرفتَ اليوم: ${Credit.spentTodayMinutes(act)} دقيقة"))
            .append(String.format("%n%nجولات اليوم: ${EventLog.countToday(act, EventLog.EARN)}"))
            .append(String.format("%nمرات الحظر اليوم: ${EventLog.countToday(act, EventLog.BLOCK)}"))
            .append(String.format("%nأذانات اليوم: ${EventLog.countToday(act, EventLog.ADHAN)}"))
            .append(String.format("%n%nالجولة الآن: ${ChallengeLock.reps(act)} ضغطة = ${Credit.rewardMinutes(act)} دقيقة"))
            .toString()
    }

    private fun renderApps() {
        if (!::appsBox.isInitialized) return
        val list = Credit.perApp(act)
        if (list.isEmpty()) {
            appsBox.text = "لم يُصرف وقت على أي تطبيق بعد."
            return
        }
        val total = list.sumOf { it.second }.coerceAtLeast(1)
        val sb = StringBuilder()
        list.take(12).forEachIndexed { i, (pkg, mins) ->
            val pct = mins * 100 / total
            val bars = "▇".repeat((pct / 10).coerceAtLeast(1))
            if (i > 0) sb.append(String.format("%n"))
            sb.append("${label(pkg)} — $mins د  ($pct٪)")
            sb.append(String.format("%n$bars"))
        }
        appsBox.text = sb.toString()
    }

    private fun renderLog() {
        if (!::logBox.isInitialized) return
        logBox.removeAllViews()
        val events = EventLog.all(act)
        if (events.isEmpty()) {
            logBox.addView(TextView(act).apply {
                text = "لا أحداث بعد."
                textSize = 13.5f
                setTextColor(Color.parseColor("#6E8F82"))
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setBackgroundColor(Color.parseColor("#0A3227"))
            })
            return
        }
        events.take(120).forEach { e ->
            logBox.addView(TextView(act).apply {
                text = "${e.icon}  ${EventLog.stamp(e.at)}   ${e.text}"
                textSize = 13f
                setTextColor(Color.parseColor(colorFor(e.icon)))
                setPadding(dp(12), dp(9), dp(12), dp(9))
                gravity = Gravity.START
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(2) })
        }
    }

    private fun colorFor(icon: String) = when (icon) {
        EventLog.EARN -> "#10B981"
        EventLog.BLOCK -> "#EF4444"
        EventLog.ADMIN -> "#F0D98A"
        EventLog.PRAYER, EventLog.ADHAN -> "#D4AF37"
        else -> "#9DBDB0"
    }

    private fun label(pkg: String): String = try {
        val pm = act.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun isServiceOn(): Boolean {
        val flat = android.provider.Settings.Secure.getString(
            act.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return flat.contains(act.packageName + "/" + BlockerService::class.java.name)
    }
}
