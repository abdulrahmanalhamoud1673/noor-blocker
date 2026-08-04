package com.noor.blocker

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * تحدّي الاستغفار — حظر دوري يُفكّ بضغطات وذكر.
 *
 * المنطق بسيط ومتين: نحفظ لحظة انتهاء السماح `clearedUntil`.
 * ما دام الوقت الحالي بعدها فالتطبيقات محظورة. وعند إتمام
 * التحدّي نضيف مدة الفترة فيُفتح إلى الجولة التالية.
 * لا نعتمد على منبّه لتفعيل الحظر — الحالة محسوبة من الوقت وحده.
 */
object ChallengeLock {

    private const val REQUEST_CODE = 7002
    const val CHANNEL_ID = "noor_challenge"

    fun enabled(c: Context) = Prefs.p(c).getBoolean("chEnabled", false)
    fun setEnabled(c: Context, b: Boolean) {
        Prefs.p(c).edit().putBoolean("chEnabled", b).apply()
        if (b) clearFor(c) else { cancelAlarm(c); Credit.stopSpending(c) }
    }

    /** كل كم دقيقة يعود الحظر — بقي للتوافق مع الإصدارات القديمة */
    fun intervalMin(c: Context) = Prefs.p(c).getInt("chInterval", 30)
    fun setIntervalMin(c: Context, m: Int) {
        Prefs.p(c).edit().putInt("chInterval", m).apply()
    }

    fun reps(c: Context) = Prefs.p(c).getInt("chReps", 10)
    fun setReps(c: Context, n: Int) = Prefs.p(c).edit().putInt("chReps", n).apply()

    fun phrase(c: Context): String =
        Prefs.p(c).getString("chPhrase", "أَسْتَغْفِرُ اللهَ") ?: "أَسْتَغْفِرُ اللهَ"
    fun setPhrase(c: Context, s: String) = Prefs.p(c).edit().putString("chPhrase", s).apply()

    /**
     * هل الحظر فعّال الآن؟
     * لم يعد سؤالاً عن الساعة بل عن الرصيد: تُحظر حين ينفد.
     */
    fun active(c: Context): Boolean = enabled(c) && Credit.isEmpty(c)

    /** المتبقي من رصيدك بالثواني */
    fun secondsUntilBlock(c: Context): Long = Credit.remainingSec(c).toLong()

    /** أتمّ الجولة — يُضاف الرصيد ويُعاد ضبط العدّاد */
    fun clearFor(c: Context): Int = Credit.award(c)

    private fun scheduleAlarm(c: Context, at: Long) {
        if (!enabled(c)) return
        createChannel(c)
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            c, REQUEST_CODE, Intent(c, ChallengeReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancelAlarm(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(PendingIntent.getBroadcast(
            c, REQUEST_CODE, Intent(c, ChallengeReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ))
    }

    fun createChannel(c: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(android.app.NotificationChannel(
            CHANNEL_ID, "تحدّي الاستغفار", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "ينبّهك عند عودة الحظر الدوري" })
    }
}

/** ينبّه عند عودة الحظر */
class ChallengeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!ChallengeLock.enabled(ctx)) return
        ChallengeLock.createChannel(ctx)

        val open = PendingIntent.getActivity(
            ctx, 3,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                Notification.Builder(ctx, ChallengeLock.CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(ctx)

        val n = builder
            .setSmallIcon(R.drawable.ic_launcher_noor)
            .setContentTitle("حان وقت الاستغفار 💪")
            .setContentText("${ChallengeLock.reps(ctx)} ضغطات مع الذكر ليُفكّ الحظر")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()

        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(1002, n)
    }
}
