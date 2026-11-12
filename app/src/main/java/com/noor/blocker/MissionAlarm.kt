package com.noor.blocker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * منبّه المهمّات — منبّه عاديّ بكل شيء إلا طريقة إسكاته.
 *
 * تضبط الساعة بيدك كأي منبّه: الخامسة صباحاً، وأيّام الأسبوع التي
 * يتكرّر فيها. وحين يرنّ لا يوجد زرّ إيقاف: يطلب منك تصوير أشياء
 * في بيتك واحداً بعد واحد — الثلاجة، مغسلة الحمّام — فلا سبيل إلى
 * إسكاته وأنت في الفراش. وحين تقف على قدميك في المطبخ يكون النوم
 * قد ذهب.
 *
 * ولماذا قائمة منبّهات لا منبّه واحد؟ لأن أيام الأسبوع تختلف:
 * دوام الصباح غير يوم الجمعة. كما في منبّه الهاتف تماماً.
 *
 * ولكلّ منبّه موعدٌ واحد مجدول في كل مرة، يُجدَّد بعد أن يرنّ —
 * أخفّ على البطارية من جدولة أسبوع كامل، ويبقى دقيقاً.
 */
object MissionAlarm {

    private const val LIST = "almList"
    private const val BASE_REQ = 7100
    const val EXTRA_ID = "almId"

    /** أسماء الأيام بترتيب Calendar: الأحد أولاً */
    val DAY_NAMES = arrayOf("ح", "ن", "ث", "ر", "خ", "ج", "س")
    val DAY_FULL = arrayOf("الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت")

    /** منبّه واحد: ساعة ودقيقة وأيام (قناع بتّي) ومفتاح تشغيل */
    class Item(val id: Int, var h: Int, var m: Int, var days: Int, var on: Boolean) {
        /** لا أيام محدّدة = مرّة واحدة ثم يُطفأ وحده */
        val once get() = days == 0

        fun json(): JSONObject = JSONObject()
            .put("id", id).put("h", h).put("m", m).put("d", days).put("on", on)

        /** «٥:٠٠ ص» */
        fun timeText(): String {
            val am = h < 12
            val h12 = if (h % 12 == 0) 12 else h % 12
            return String.format("%d:%02d %s", h12, m, if (am) "ص" else "م")
        }

        /** «كل يوم» أو «الأحد، الثلاثاء» أو «مرة واحدة» */
        fun daysText(): String {
            if (once) return "مرة واحدة"
            if (days == 0b1111111) return "كل يوم"
            // أيام الدوام في الأردن: الأحد إلى الخميس
            if (days == 0b0011111) return "أيام الدوام"
            if (days == 0b1100000) return "الجمعة والسبت"
            val names = (0..6).filter { (days shr it) and 1 == 1 }.map { DAY_FULL[it] }
            return names.joinToString("، ")
        }
    }

    /* ─────────── التخزين ─────────── */

    fun all(c: Context): MutableList<Item> {
        val out = mutableListOf<Item>()
        try {
            val arr = JSONArray(Prefs.p(c).getString(LIST, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Item(o.getInt("id"), o.getInt("h"), o.getInt("m"),
                    o.optInt("d", 0), o.optBoolean("on", true)))
            }
        } catch (_: Exception) {}
        out.sortWith(compareBy({ it.h }, { it.m }))
        return out
    }

    private fun save(c: Context, list: List<Item>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.json()) }
        Prefs.p(c).edit().putString(LIST, arr.toString()).apply()
    }

    fun add(c: Context, h: Int, m: Int, days: Int): Item {
        val list = all(c)
        val id = (list.maxOfOrNull { it.id } ?: 0) + 1
        val it = Item(id, h, m, days, true)
        list.add(it)
        save(c, list)
        scheduleAll(c)
        EventLog.add(c, EventLog.SETTING, "أُضيف منبّه ${it.timeText()}")
        return it
    }

    fun remove(c: Context, id: Int) {
        cancelOne(c, id)
        save(c, all(c).filter { it.id != id })
        scheduleAll(c)
        EventLog.add(c, EventLog.SETTING, "حُذف منبّه")
    }

    fun setOn(c: Context, id: Int, on: Boolean) {
        val list = all(c)
        list.firstOrNull { it.id == id }?.let { it.on = on }
        save(c, list)
        if (!on) cancelOne(c, id)
        scheduleAll(c)
    }

    fun setDays(c: Context, id: Int, days: Int) {
        val list = all(c)
        list.firstOrNull { it.id == id }?.let { it.days = days }
        save(c, list)
        scheduleAll(c)
    }

    fun setTime(c: Context, id: Int, h: Int, m: Int) {
        val list = all(c)
        list.firstOrNull { it.id == id }?.let { it.h = h; it.m = m }
        save(c, list)
        scheduleAll(c)
    }

    /* ─────────── المواعيد ─────────── */

    /**
     * متى يرنّ هذا المنبّه بعد الآن؟
     *
     * نبدأ من وقت اليوم: إن كان قد مضى بحثنا عن أقرب يوم تالٍ
     * مسموح. وبلا أيام محدّدة يكون الغد إن مضى موعد اليوم.
     */
    fun nextAt(h: Int, m: Int, days: Int, from: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = from
        c.set(Calendar.HOUR_OF_DAY, h)
        c.set(Calendar.MINUTE, m)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)

        if (days == 0) {
            if (c.timeInMillis <= from + 1000) c.add(Calendar.DAY_OF_YEAR, 1)
            return c.timeInMillis
        }
        for (i in 0..7) {
            val dow = c.get(Calendar.DAY_OF_WEEK) - 1            // الأحد = 0
            if ((days shr dow) and 1 == 1 && c.timeInMillis > from + 1000) return c.timeInMillis
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis
    }

    fun nextAt(it: Item) = nextAt(it.h, it.m, it.days)

    /** أقرب منبّه مشتغل، أو صفر إن لم يكن ثمّة منبّه */
    fun nextAny(c: Context): Long =
        all(c).filter { it.on }.map { nextAt(it) }.minOrNull() ?: 0L

    fun anyOn(c: Context) = all(c).any { it.on }

    /* ─────────── الجدولة ─────────── */

    fun scheduleAll(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        all(c).forEach { item ->
            val pi = pending(c, item.id)
            if (!item.on) { am.cancel(pi); return@forEach }
            val at = nextAt(item)
            try {
                val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
                if (exact) am.setAlarmClock(AlarmManager.AlarmClockInfo(at, pi), pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
    }

    private fun cancelOne(c: Context, id: Int) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(c, id))
    }

    /** بعد أن يرنّ: منبّه المرّة الواحدة يُطفأ وحده، والمتكرّر يُجدَّد */
    fun afterFire(c: Context, id: Int) {
        val list = all(c)
        val item = list.firstOrNull { it.id == id }
        if (item != null && item.once) {
            item.on = false
            save(c, list)
        }
        scheduleAll(c)
    }

    private fun pending(c: Context, id: Int) = PendingIntent.getBroadcast(
        c, BASE_REQ + id,
        Intent(c, MissionReceiver::class.java).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

/**
 * ينطلق في وقته: يرفع الصوت ثمّ يجدّد نفسه.
 *
 * لا نفتح الشاشة من هنا مباشرة فحسب، فأندرويد ١٠ فما فوق يمنع فتح
 * الشاشات من الخلفية؛ الطريق المضمون هو إشعار full-screen من
 * الخدمة الأمامية. ونجرّب الفتح المباشر أيضاً — إن نجح فهو أسرع.
 */
class MissionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(MissionAlarm.EXTRA_ID, -1)
        val item = MissionAlarm.all(ctx).firstOrNull { it.id == id }

        if (item != null && item.on) {
            EventLog.add(ctx, EventLog.ADHAN, "رنّ منبّه ${item.timeText()}")
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
        MissionAlarm.afterFire(ctx, id)
    }
}
