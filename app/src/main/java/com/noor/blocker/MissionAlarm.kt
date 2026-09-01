package com.noor.blocker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * منبّه الفجر بالمهمّات.
 *
 * منبّه لا يسكت بضغطة: يرنّ حتى تُصوّر أشياءَ في بيتك يختارها
 * التطبيق عشوائياً. لا سبيل إلى إسكاته وأنت في الفراش، فحين تقف
 * على قدميك في المطبخ يكون النوم قد ذهب.
 *
 * نجدول منبّهاً واحداً للمرة القادمة فقط ثم يُجدِّد نفسه، كما في
 * منبّه الأذان — أخفّ على البطارية ويبقى دقيقاً.
 */
object MissionAlarm {

    private const val REQUEST_CODE = 7003
    private const val ENABLED = "almEnabled"
    private const val OFFSET = "almOffset"     // دقائق قبل/بعد الفجر

    fun enabled(c: Context) = Prefs.p(c).getBoolean(ENABLED, false)
    fun setEnabled(c: Context, b: Boolean) {
        Prefs.p(c).edit().putBoolean(ENABLED, b).apply()
        if (b) schedule(c) else cancel(c)
        EventLog.add(c, EventLog.SETTING,
            if (b) "فُعّل منبّه المهمّات" else "أُوقف منبّه المهمّات")
    }

    /** بالسالب قبل الفجر وبالموجب بعده */
    fun offsetMin(c: Context) = Prefs.p(c).getInt(OFFSET, 0)
    fun setOffsetMin(c: Context, m: Int) {
        Prefs.p(c).edit().putInt(OFFSET, m).apply()
        schedule(c)
    }

    /** متى يرنّ في المرة القادمة؟ */
    fun nextAt(c: Context): Long {
        val cal = Calendar.getInstance()
        val fajrToday = PrayerLock.todayTimes(c)[0]
        val at = fajrAt(cal, fajrToday, offsetMin(c))
        if (at > System.currentTimeMillis() + 30_000) return at

        // مضى فجر اليوم → فجر الغد
        val t = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val tz = java.util.TimeZone.getDefault().getOffset(t.timeInMillis) / 3600000.0
        val fajrTomorrow = PrayerTimes.calculate(t, Prefs.lat(c), Prefs.lng(c), tz)[0]
        return fajrAt(t, fajrTomorrow, offsetMin(c))
    }

    private fun fajrAt(day: Calendar, minutes: Double, offset: Int): Long {
        val c = day.clone() as Calendar
        c.set(Calendar.HOUR_OF_DAY, (minutes / 60).toInt())
        c.set(Calendar.MINUTE, (minutes % 60).toInt())
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.MINUTE, offset)
        return c.timeInMillis
    }

    fun schedule(c: Context) {
        if (!enabled(c)) { cancel(c); return }
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(c)
        val at = nextAt(c)
        try {
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (exact) am.setAlarmClock(AlarmManager.AlarmClockInfo(at, pi), pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(c))
    }

    private fun pending(c: Context) = PendingIntent.getBroadcast(
        c, REQUEST_CODE, Intent(c, MissionReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

/**
 * ينطلق في وقته: يرفع الصوت ثمّ يجدّد نفسه للغد.
 *
 * لا نفتح الشاشة من هنا مباشرة فحسب، فأندرويد ١٠ فما فوق يمنع فتح
 * الشاشات من الخلفية؛ الطريق المضمون هو إشعار full-screen من
 * الخدمة الأمامية. ونجرّب الفتح المباشر أيضاً — إن نجح فهو أسرع.
 */
class MissionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (MissionAlarm.enabled(ctx)) {
            EventLog.add(ctx, EventLog.ADHAN, "رنّ منبّه المهمّات")
            MissionRinger.start(ctx)
            try {
                ctx.startActivity(Intent(ctx, AlarmActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
                })
            } catch (_: Exception) { /* يكفينا إشعار الخدمة */ }
        }
        MissionAlarm.schedule(ctx)
    }
}
