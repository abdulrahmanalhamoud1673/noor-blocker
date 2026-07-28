package com.noor.blocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * يرفع الأذان بصوت مؤذّن حقيقي عند دخول الوقت.
 *
 * لماذا خدمة أمامية وليس مجرّد صوت للإشعار؟
 * صوت الإشعار في أندرويد يُقطع بعد ثوانٍ قليلة، والأذان دقيقتان ونصف.
 * الخدمة الأمامية تُبقي التشغيل حيّاً حتى ينتهي الأذان ولو كان الهاتف
 * في جيبك والتطبيق مغلقاً، وتعطيك إشعاراً فيه زرّ لإيقافه فوراً.
 *
 * ونستخدم USAGE_ALARM لا NOTIFICATION، ليخرج على صوت المنبّه —
 * فيُسمع حتى لو كان الهاتف على الوضع الصامت أو خافت الصوت.
 */
class AdhanPlayer : Service() {

    private var player: MediaPlayer? = null
    private var wake: PowerManager.WakeLock? = null

    companion object {
        const val CHANNEL_ID = "noor_adhan_play"
        const val ACTION_STOP = "com.noor.blocker.STOP_ADHAN"
        const val EXTRA_PRAYER = "prayer"
        private const val NOTIF_ID = 1002

        /** يشغّل الأذان لصلاة باسمها */
        fun play(ctx: Context, prayer: String) {
            val i = Intent(ctx, AdhanPlayer::class.java).putExtra(EXTRA_PRAYER, prayer)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, AdhanPlayer::class.java).setAction(ACTION_STOP))
        }

        fun createChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "الأذان", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "يرفع الأذان عند دخول وقت الصلاة"
                    // الصوت يخرج من مشغّل الخدمة نفسها لا من الإشعار
                    setSound(null, null)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 250, 400)
                }
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val prayer = intent?.getStringExtra(EXTRA_PRAYER) ?: "الصلاة"
        createChannel(this)
        startForeground(NOTIF_ID, buildNotification(prayer))

        // نُبقي المعالج مستيقظاً حتى ينتهي الأذان ولو نام الهاتف
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "noor:adhan").apply {
                acquire(5 * 60 * 1000L)
            }
        } catch (_: Exception) {}

        startAudio()
        return START_NOT_STICKY
    }

    private fun startAudio() {
        stopAudio()
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // إن كان صوت المنبّه منخفضاً جداً نرفعه إلى نصف الحدّ الأقصى
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) < max / 3) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, max / 2, 0)
            }

            // خصائص الصوت تُمرَّر عند الإنشاء: ضبطها بعد التحضير لا يؤثّر
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            player = MediaPlayer.create(this, R.raw.adhan, attrs, am.generateAudioSessionId())
            if (player == null) { stopSelf(); return }

            player?.apply {
                isLooping = false
                setOnCompletionListener { stopSelf() }
                setOnErrorListener { _, _, _ -> stopSelf(); true }
                start()
            }
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun stopAudio() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    private fun buildNotification(prayer: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val silence = PendingIntent.getService(
            this, 1,
            Intent(this, AdhanPlayer::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)

        return b.setSmallIcon(R.drawable.ic_launcher_noor)
            .setContentTitle("حان الآن وقت صلاة $prayer")
            .setContentText("الله أكبر… توقّف وأقم الصلاة 🕌")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                @Suppress("DEPRECATION")
                Notification.Action.Builder(
                    R.drawable.ic_launcher_noor, "إيقاف الأذان", silence
                ).build()
            )
            .build()
    }

    override fun onDestroy() {
        stopAudio()
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
        wake = null
        super.onDestroy()
    }
}
