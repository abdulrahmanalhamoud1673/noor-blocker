package com.noor.blocker

import android.content.Context
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.ceil

/** إعدادات محفوظة على الجهاز */
object Prefs {
    private const val FILE = "noor_blocker"

    // ليست خاصة: يستخدمها ChallengeLock أيضاً
    fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun blockedApps(c: Context): MutableSet<String> =
        HashSet(p(c).getStringSet("blocked", emptySet()) ?: emptySet())

    fun setBlockedApps(c: Context, apps: Set<String>) {
        p(c).edit().putStringSet("blocked", HashSet(apps)).apply()
    }

    fun isBlocked(c: Context, pkg: String) = blockedApps(c).contains(pkg)

    fun lockMinutes(c: Context) = p(c).getInt("lockMinutes", 15)
    fun setLockMinutes(c: Context, m: Int) = p(c).edit().putInt("lockMinutes", m).apply()

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

    fun enabled(c: Context) = p(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, b: Boolean) = p(c).edit().putBoolean("enabled", b).apply()

    /** آخر إصدار مُسحت عنده ذاكرة الويب */
    fun lastWebClean(c: Context): String = p(c).getString("webClean", "") ?: ""
    fun setLastWebClean(c: Context, v: String) = p(c).edit().putString("webClean", v).apply()

    /** تنبيه الأذان */
    fun notifyEnabled(c: Context) = p(c).getBoolean("notify", true)
    fun setNotifyEnabled(c: Context, b: Boolean) = p(c).edit().putBoolean("notify", b).apply()

    /** يسجّل أنه صلّى هذه الصلاة، فلا يُقفل عليه مرة أخرى */
    fun markPrayed(c: Context, key: String) = p(c).edit().putString("prayed", key).apply()
    fun prayedKey(c: Context): String = p(c).getString("prayed", "") ?: ""
}

/**
 * معلومات القفل الحالي.
 * @param secondsLeft المتبقي حتى تنتهي نافذة القفل
 * @param elapsed كم مضى من دخول الوقت — يحدّد متى يُسمح بالفتح
 * @param rakaat عدد ركعات هذه الصلاة
 */
class LockInfo(
    val prayerName: String,
    val secondsLeft: Int,
    val elapsed: Int,
    val rakaat: Int,
    val key: String
) {
    /**
     * أقل مدة معقولة لأداء الصلاة — لا يظهر زر الفتح قبلها.
     * دقيقة وربع لكل ركعة تقريباً، وهي مدة متأنّية لا مستعجلة.
     */
    val minSeconds: Int get() = rakaat * 75

    /** هل مضى وقت يكفي لأداء الصلاة فعلاً؟ */
    val mayUnlock: Boolean get() = elapsed >= minSeconds

    /** المتبقي حتى يُسمح بالفتح */
    val untilUnlock: Int get() = (minSeconds - elapsed).coerceAtLeast(0)
}

object PrayerLock {

    val NAMES = arrayOf("الفجر", "الشروق", "الظهر", "العصر", "المغرب", "العشاء")
    private val KEYS = arrayOf("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha")

    /** الصلوات التي تُفعّل القفل (بدون الشروق) */
    private val LOCKING = intArrayOf(0, 2, 3, 4, 5)

    /** عدد ركعات كل صلاة بترتيب NAMES */
    private val RAKAAT = intArrayOf(2, 0, 4, 4, 3, 4)

    fun todayTimes(c: Context): DoubleArray {
        val cal = Calendar.getInstance()
        val tz = TimeZone.getDefault().getOffset(cal.timeInMillis) / 3600000.0
        return PrayerTimes.calculate(cal, Prefs.lat(c), Prefs.lng(c), tz)
    }

    private fun dayStamp(cal: Calendar) = String.format(
        "%04d-%02d-%02d",
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH) + 1,
        cal.get(Calendar.DAY_OF_MONTH)
    )

    /**
     * هل نحن الآن داخل نافذة قفل؟
     * ترجع null إذا لا قفل، أو معلومات القفل إذا كان مفعّلاً.
     */
    fun current(c: Context): LockInfo? {
        if (!Prefs.enabled(c)) return null

        val cal = Calendar.getInstance()
        val times = todayTimes(c)
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60.0 +
                  cal.get(Calendar.MINUTE) +
                  cal.get(Calendar.SECOND) / 60.0
        val duration = Prefs.lockMinutes(c).toDouble()
        val stamp = dayStamp(cal)

        for (i in LOCKING) {
            val t = times[i]
            if (t.isNaN()) continue
            if (now >= t && now < t + duration) {
                val key = stamp + "_" + KEYS[i]
                if (Prefs.prayedKey(c) == key) return null   // صلّى بالفعل
                val secs = ceil((t + duration - now) * 60.0).toInt()
                val elapsed = ((now - t) * 60.0).toInt()
                return LockInfo(NAMES[i], secs, elapsed, RAKAAT[i], key)
            }
        }
        return null
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
