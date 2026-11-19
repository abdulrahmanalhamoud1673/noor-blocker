package com.noor.blocker

import android.content.Context
import java.util.Calendar
import java.util.TimeZone

/** إعدادات محفوظة على الجهاز */
object Prefs {
    private const val FILE = "noor_blocker"

    fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /* ─────────── الموقع ─────────── */
    fun lat(c: Context) = p(c).getFloat("lat", 31.9539f).toDouble()
    fun lng(c: Context) = p(c).getFloat("lng", 35.9106f).toDouble()
    fun cityName(c: Context): String = p(c).getString("city", "عمّان") ?: "عمّان"

    fun setCity(c: Context, name: String, lat: Double, lng: Double) {
        p(c).edit()
            .putString("city", name)
            .putFloat("lat", lat.toFloat())
            .putFloat("lng", lng.toFloat())
            .apply()
    }

    /** آخر إصدار مُسحت عنده ذاكرة الويب */
    fun lastWebClean(c: Context): String = p(c).getString("webClean", "") ?: ""
    fun setLastWebClean(c: Context, v: String) = p(c).edit().putString("webClean", v).apply()

    /* ─────────── تنبيه الأذان ─────────── */
    fun notifyEnabled(c: Context) = p(c).getBoolean("notify", true)
    fun setNotifyEnabled(c: Context, b: Boolean) = p(c).edit().putBoolean("notify", b).apply()

    /** رفع الأذان بصوت مؤذّن بدل نغمة إشعار قصيرة */
    fun adhanSound(c: Context) = p(c).getBoolean("adhanSound", true)
    fun setAdhanSound(c: Context, b: Boolean) = p(c).edit().putBoolean("adhanSound", b).apply()
}

/** أوقات الصلاة لليوم ومكانها — لا قفل بعد اليوم، تنبيهٌ فحسب */
object PrayerLock {

    val NAMES = arrayOf("الفجر", "الشروق", "الظهر", "العصر", "المغرب", "العشاء")

    fun todayTimes(c: Context): DoubleArray {
        val cal = Calendar.getInstance()
        val tz = TimeZone.getDefault().getOffset(cal.timeInMillis) / 3600000.0
        return PrayerTimes.calculate(cal, Prefs.lat(c), Prefs.lng(c), tz)
    }

    /** الصلاة القادمة: الاسم والوقت نصاً */
    fun next(c: Context): Pair<String, String> {
        val cal = Calendar.getInstance()
        val times = todayTimes(c)
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60.0 + cal.get(Calendar.MINUTE)
        for (i in times.indices) {
            if (!times[i].isNaN() && times[i] > now) {
                return Pair(NAMES[i], PrayerTimes.format(times[i]))
            }
        }
        return Pair(NAMES[0], PrayerTimes.format(times[0]))
    }
}
