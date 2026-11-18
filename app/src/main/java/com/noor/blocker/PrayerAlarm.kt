package com.noor.blocker

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import java.util.Calendar

/**
 * تنبيه الأذان — يعمل حتى لو كان التطبيق مغلقاً.
 *
 * الفكرة: نجدول منبّهاً واحداً للصلاة القادمة فقط. عندما ينطلق
 * يعرض الإشعار ثم يجدّد نفسه للصلاة التي بعدها. هذا أخف على
 * البطارية من جدولة خمسة منبّهات، ويبقى دقيقاً.
 */
object PrayerAlarm {

    const val CHANNEL_ID = "noor_adhan"
    private const val REQUEST_CODE = 7001
    const val EXTRA_PRAYER = "prayer_name"

    /* أسماء الصلوات التي تُنبّه (بدون الشروق) */
    private val ALERTING = intArrayOf(0, 2, 3, 4, 5)

    fun createChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return

        val ch = NotificationChannel(
            CHANNEL_ID,
            "تنبيه الأذان",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "ينبّهك عند دخول وقت كل صلاة"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 350, 200, 350)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                Notification.AUDIO_ATTRIBUTES_DEFAULT
            )
        }
        nm.createNotificationChannel(ch)
    }

    /**
     * متى الصلاة القادمة؟
     * @return زوج من (اسم الصلاة، وقتها بالمللي ثانية)
     */
    fun nextPrayerAt(ctx: Context): Pair<String, Long> {
        val now = Calendar.getInstance()
        val times = PrayerLock.todayTimes(ctx)
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60.0 +
                     now.get(Calendar.MINUTE) + now.get(Calendar.SECOND) / 60.0

        for (i in ALERTING) {
            val t = times[i]
            if (!t.isNaN() && t > nowMin) return Pair(PrayerLock.NAMES[i], atMinutes(t, 0))
        }

        // مضت صلوات اليوم كلها → فجر الغد
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val tz = java.util.TimeZone.getDefault().getOffset(tomorrow.timeInMillis) / 3600000.0
        val tTimes = PrayerTimes.calculate(tomorrow, Prefs.lat(ctx), Prefs.lng(ctx), tz)
        return Pair(PrayerLock.NAMES[0], atMinutes(tTimes[0], 1))
    }

    /** يحوّل «دقائق منذ منتصف الليل» إلى وقت فعلي */
    private fun atMinutes(minutes: Double, dayOffset: Int): Long {
        val c = Calendar.getInstance()
        if (dayOffset != 0) c.add(Calendar.DAY_OF_YEAR, dayOffset)
        c.set(Calendar.HOUR_OF_DAY, (minutes / 60).toInt())
        c.set(Calendar.MINUTE, (minutes % 60).toInt())
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** يجدول تنبيه الصلاة القادمة */
    fun schedule(ctx: Context) {
        if (!Prefs.notifyEnabled(ctx)) { cancel(ctx); return }

        createChannel(ctx)
        val (name, at) = nextPrayerAt(ctx)

        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ctx, AdhanReceiver::class.java).apply {
            putExtra(EXTRA_PRAYER, name)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getBroadcast(ctx, REQUEST_CODE, intent, flags)

        try {
            // نستخدم التنبيه الدقيق إن سُمح به، وإلا التقريبي — الأهم ألا نفشل
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx, REQUEST_CODE,
            Intent(ctx, AdhanReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
    }
}

/**
 * يستقبل التنبيه: يجدول التالي أولاً، ثمّ يرفع الأذان ويقفل الهاتف.
 *
 * ولماذا الجدولة أولاً؟ لأنها كانت آخر سطر، فكان أي عطل قبلها يقطع
 * سلسلة الأذان إلى الأبد: لا أذان، ولا موعد بعده. الآن مهما حدث
 * بعدها، أذانُ الصلاة القادمة محجوز.
 */
class AdhanReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // أوّلاً وقبل كل شيء — لا شيء يقطع السلسلة
        try { PrayerAlarm.schedule(ctx) } catch (_: Exception) {}

        val name = intent.getStringExtra(PrayerAlarm.EXTRA_PRAYER) ?: "الصلاة"
        PrayerAlarm.createChannel(ctx)
        EventLog.add(ctx, EventLog.ADHAN, "أذان " + name)

        if (Prefs.adhanSound(ctx)) {
            // أذان كامل بصوت مؤذّن عبر خدمة أمامية حتى لا يُقطع بعد ثوانٍ.
            // وأندرويد ١٢ قد يرفض تشغيلها من الخلفية، فنُمسك الرفض
            // ونرفع الأذان بإشعار ملء الشاشة بدلاً من أن نسقط بلا صوت.
            try {
                AdhanPlayer.play(ctx, name)
            } catch (e: Exception) {
                EventLog.add(ctx, EventLog.ADHAN, "تعذّر تشغيل الخدمة — إشعار بدلاً منها")
                notifyAdhan(ctx, name, loud = true)
            }
        } else {
            notifyAdhan(ctx, name, loud = false)
        }

        // قفل الهاتف بعد ثوانٍ قليلة.
        //
        // كان هذا postDelayed عادياً، والنظام يُجمّد التطبيق فور انتهاء
        // onReceive — فلا يأتي الموعد أبداً والهاتف نائم. goAsync مع قفل
        // إيقاظ يُبقي المستقبِل حيّاً حتى ننتهي فعلاً.
        if (!Prefs.enabled(ctx) || PrayerLock.current(ctx) == null) return

        val pending = goAsync()
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val wake = try {
            pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "noor:adhanLock")
                .apply { acquire(20_000L) }
        } catch (e: Exception) { null }

        val h = android.os.Handler(android.os.Looper.getMainLooper())
        h.postDelayed({
            try {
                if (PrayerLock.current(ctx) != null) {
                    EventLog.add(ctx, EventLog.PRAYER,
                        "قُفل الهاتف لصلاة " + name + " — " + Prefs.lockMinutes(ctx) + " دقيقة")
                    ctx.startActivity(Intent(ctx, LockActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                        )
                    })
                }
            } catch (_: Exception) {}

            // ثم نُطفئ الشاشة فعلياً: إن كان الهاتف بيده أُقفل،
            // وإن كان مغلقاً بقي مغلقاً. نمهله ثوانيَ ليقرأ السبب.
            h.postDelayed({
                try { if (PrayerLock.current(ctx) != null) ScreenLock.lockNow(ctx) } catch (_: Exception) {}
                try { if (wake?.isHeld == true) wake.release() } catch (_: Exception) {}
                pending.finish()
            }, 6000)
        }, 1500)
    }

    /** إشعار الأذان — بصوت عالٍ وملء الشاشة حين تتعذّر الخدمة */
    private fun notifyAdhan(ctx: Context, name: String, loud: Boolean) {
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(ctx, PrayerAlarm.CHANNEL_ID) else Notification.Builder(ctx)

        b.setSmallIcon(R.drawable.ic_launcher_noor)
            .setContentTitle("حان الآن وقت صلاة " + name)
            .setContentText("توقّف وأقم الصلاة 🕌")
            .setContentIntent(open)
            .setAutoCancel(true)
        if (loud) {
            b.setCategory(Notification.CATEGORY_ALARM)
            b.setFullScreenIntent(open, true)
        }

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1001, b.build())
    }
}

/** يعيد الجدولة بعد إعادة تشغيل الهاتف، وإلا ضاعت المنبّهات */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            PrayerAlarm.schedule(ctx)
        }
    }
}
