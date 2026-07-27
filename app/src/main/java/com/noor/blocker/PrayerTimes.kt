package com.noor.blocker

import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * حساب أوقات الصلاة فلكياً — بدون إنترنت.
 * النتيجة: دقائق منذ منتصف الليل لكل من
 * [0] الفجر [1] الشروق [2] الظهر [3] العصر [4] المغرب [5] العشاء
 */
object PrayerTimes {

    private fun fix(a: Double, b: Double): Double {
        val r = a - b * floor(a / b)
        return if (r < 0) r + b else r
    }

    private fun fixAngle(a: Double) = fix(a, 360.0)
    private fun fixHour(a: Double) = fix(a, 24.0)

    private fun dtr(d: Double) = d * PI / 180.0
    private fun rtd(r: Double) = r * 180.0 / PI
    private fun sinD(d: Double) = sin(dtr(d))
    private fun cosD(d: Double) = cos(dtr(d))
    private fun tanD(d: Double) = tan(dtr(d))

    private fun julian(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2.0 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    private class Sun(val decl: Double, val eqt: Double)

    private fun sunPosition(jd: Double): Sun {
        val d = jd - 2451545.0
        val g = fixAngle(357.529 + 0.98560028 * d)
        val q = fixAngle(280.459 + 0.98564736 * d)
        val l = fixAngle(q + 1.915 * sinD(g) + 0.020 * sinD(2 * g))
        val e = 23.439 - 0.00000036 * d
        val ra = fixHour(rtd(atan2(cosD(e) * sinD(l), cosD(l))) / 15.0)
        val decl = rtd(asin(sinD(e) * sinD(l)))
        return Sun(decl, q / 15.0 - ra)
    }

    fun calculate(
        cal: Calendar,
        lat: Double,
        lng: Double,
        tzHours: Double,
        fajrAngle: Double = 18.0,
        ishaAngle: Double = 18.0
    ): DoubleArray {

        val jd = julian(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        ) - lng / (15.0 * 24.0)

        fun midDay(t: Double): Double = fixHour(12.0 - sunPosition(jd + t).eqt)

        fun sunAngleTime(angle: Double, t: Double, ccw: Boolean): Double {
            val decl = sunPosition(jd + t).decl
            val noon = midDay(t)
            val inner = (-sinD(angle) - sinD(decl) * sinD(lat)) / (cosD(decl) * cosD(lat))
            if (inner > 1.0 || inner < -1.0) return Double.NaN
            val delta = rtd(acos(inner)) / 15.0
            return if (ccw) noon - delta else noon + delta
        }

        fun asrTime(factor: Double, t: Double): Double {
            val decl = sunPosition(jd + t).decl
            val angle = -rtd(atan(1.0 / (factor + tanD(abs(lat - decl)))))
            return sunAngleTime(angle, t, false)
        }

        var fajr = 5.0 / 24.0
        var sunrise = 6.0 / 24.0
        var dhuhr = 12.0 / 24.0
        var asr = 13.0 / 24.0
        var maghrib = 18.0 / 24.0
        var isha = 19.0 / 24.0

        repeat(3) {
            val f = fajr; val su = sunrise; val dh = dhuhr
            val a = asr; val mg = maghrib; val ish = isha

            fajr = sunAngleTime(fajrAngle, f, true) / 24.0
            sunrise = sunAngleTime(0.833, su, true) / 24.0
            dhuhr = midDay(dh) / 24.0
            asr = asrTime(1.0, a) / 24.0
            maghrib = sunAngleTime(0.833, mg, false) / 24.0
            isha = sunAngleTime(ishaAngle, ish, false) / 24.0
        }

        val raw = doubleArrayOf(fajr, sunrise, dhuhr, asr, maghrib, isha)
        val out = DoubleArray(6)
        for (i in raw.indices) {
            var h = raw[i] * 24.0 + tzHours - lng / 15.0
            if (i == 2) h += 2.0 / 60.0   // احتياط دقيقتين للظهر
            out[i] = fixHour(h) * 60.0
        }
        return out
    }

    /** تحويل الدقائق إلى نص 12 ساعة */
    fun format(minutes: Double): String {
        if (minutes.isNaN()) return "--:--"
        val total = minutes.toInt()
        var h = (total / 60) % 24
        val m = total % 60
        val period = if (h >= 12) "م" else "ص"
        h %= 12
        if (h == 0) h = 12
        return String.format("%d:%02d %s", h, m, period)
    }
}
