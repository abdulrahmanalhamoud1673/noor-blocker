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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * صوت منبّه المهمّات.
 *
 * لماذا خدمة أمامية والصوتُ يمكن أن يخرج من الشاشة نفسها؟
 * لأن الشاشة قد يقتلها النظام أو يخفيها المستخدم، أما الخدمة الأمامية
 * فتبقى ترنّ. والأهم: إشعارها من نوع full-screen intent، وهو الطريق
 * الذي تعتمده تطبيقات المنبّهات كي تفتح شاشتها فوق شاشة القفل —
 * فمنذ أندرويد 10 مُنع فتحُ الشاشات من الخلفية إلا بهذا الباب.
 *
 * وفيه صمّام أمان: يسكت الصوت وحده بعد ثلاثين دقيقة. منبّهٌ لا يسكت
 * أبداً ليس ميزة بل خطر — لو انقطع الإنترنت وأنت خارج البيت ظلّ
 * يصرخ حتى تنفد البطارية. المهمّات تبقى مطلوبة، والصراخ لا يبقى.
 */
class MissionRinger : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wake: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        const val CHANNEL_ID = "noor_mission_alarm"
        const val ACTION_STOP = "com.noor.blocker.STOP_MISSION"
        private const val NOTIF_ID = 1004

        /** أقصى مدة رنين — صمّام أمان لا خيار للمستخدم فيه */
        private const val MAX_RING_MS = 30 * 60 * 1000L

        @Volatile var ringing = false
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, MissionRinger::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, MissionRinger::class.java).setAction(ACTION_STOP))
        }

        fun createChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "منبّه المهمّات", NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "منبّه لا يسكت إلا بإتمام المهمّات"
                    setSound(null, null)          // الصوت من المشغّل لا من الإشعار
                    enableVibration(false)        // الاهتزاز من الخدمة كي يستمر
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    private val giveUp = Runnable {
        EventLog.add(this, EventLog.ADHAN, "سكت المنبّه وحده بعد ٣٠ دقيقة")
        stopSelf()
    }

    /** يستأنف الصوت لو أوقفه شيء خارجي (مكالمة، سمّاعة…) */
    private val keepRinging = object : Runnable {
        override fun run() {
            try { if (player?.isPlaying == false) player?.start() } catch (_: Exception) {}
            handler.postDelayed(this, 3000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel(this)
        startForeground(NOTIF_ID, buildNotification())
        ringing = true

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "noor:ringer")
                .apply { acquire(MAX_RING_MS) }
        } catch (_: Exception) {}

        startAudio()
        startVibration()
        handler.postDelayed(giveUp, MAX_RING_MS)
        return START_STICKY   // لو قتله النظام عاد يرنّ
    }

    private fun startAudio() {
        stopAudio()
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            // منبّه بلا صوت لا معنى له — ثلاثة أرباع الحدّ الأقصى
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) < max * 3 / 4) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, max * 3 / 4, 0)
            }

            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            player = MediaPlayer.create(this, R.raw.alarm, attrs, am.generateAudioSessionId())
            player?.apply {
                isLooping = true
                setOnErrorListener { _, _, _ -> true }
                start()
            }
            handler.postDelayed(keepRinging, 3000)
        } catch (_: Exception) {}
    }

    private fun startVibration() {
        try {
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val pattern = longArrayOf(0, 700, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION") vibrator?.vibrate(pattern, 0)
            }
        } catch (_: Exception) {}
    }

    private fun stopAudio() {
        handler.removeCallbacks(keepRinging)
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    /**
     * الإشعار هو ما يفتح شاشة المهمّات فوق شاشة القفل.
     * setFullScreenIntent هي الباب المسموح به منذ أندرويد 10.
     */
    private fun buildNotification(): Notification {
        val screen = PendingIntent.getActivity(
            this, 0,
            Intent(this, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)

        return b.setSmallIcon(R.drawable.ic_launcher_noor)
            .setContentTitle("حان وقت المنبّه — قُم")
            .setContentText("لن يسكت حتى تُتمّ المهمّات")
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    override fun onDestroy() {
        ringing = false
        handler.removeCallbacks(giveUp)
        stopAudio()
        try { vibrator?.cancel() } catch (_: Exception) {}
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
        wake = null
        super.onDestroy()
    }
}
