package com.noor.blocker

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * خدمة إمكانية الوصول.
 * تراقب أي تطبيق يُفتح، وإذا كان ضمن قائمة الحظر ووقت الصلاة قائم،
 * تفتح شاشة القفل فوراً فوقه.
 *
 * لا تقرأ محتوى الشاشة إطلاقاً — فقط اسم حزمة التطبيق المفتوح.
 */
/**
 * لا تُحجب أبداً في أي وضع: الاتصال والطوارئ وجهات الاتصال، ومعها
 * الإعدادات.
 *
 * الإعدادات مفتوحة عمداً وليست سهواً: هي منفذك الوحيد لإيقاف الخدمة
 * لو حدث خلل. إغلاقها يعني احتمال أن تُحبس في هاتفك بلا مخرج، وهذا
 * ثمن لا يستحقه أي قدر من الصرامة.
 */
private val ALWAYS_ALLOWED = setOf(
    "com.android.phone",
    "com.android.incallui",
    "com.android.contacts",
    "com.android.settings",
    "com.android.emergency",
    "com.google.android.dialer",
    "com.google.android.contacts",
    "com.samsung.android.dialer",
    "com.samsung.android.incallui",
    "com.android.settings.intelligence",
    "com.samsung.android.app.telephonyui"
)

/**
 * الشاشات الرئيسية. تُحجب في قفل الصلاة (لأنه قفل للهاتف كلّه)
 * وتُترك في تحدّي الاستغفار (لأنه حظر لتطبيقات مختارة فقط).
 */
private val LAUNCHERS = setOf(
    "com.android.launcher",
    "com.android.launcher3",
    "com.google.android.apps.nexuslauncher",
    "com.sec.android.app.launcher",
    "com.miui.home",
    "com.huawei.android.launcher",
    "com.oppo.launcher",
    "com.oneplus.launcher",
    "net.oneplus.launcher",
    "com.realme.launcher",
    "com.transsion.XOSLauncher",
    "com.microsoft.launcher"
)

class BlockerService : AccessibilityService() {

    private var lastKickAt = 0L
    private val handler = Handler(Looper.getMainLooper())

    /**
     * يراقب الرصيد أثناء الصرف.
     * بدونه لن يظهر القفل إلا عند تبديل التطبيق التالي، فيستمر
     * الاستخدام بعد نفاد الرصيد إلى أن يخرج بنفسه.
     */
    private val watcher = object : Runnable {
        override fun run() {
            if (!Credit.isSpending(this@BlockerService)) return
            if (Credit.isEmpty(this@BlockerService)) {
                Credit.stopSpending(this@BlockerService)
                showLock()
                return
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun startWatching() {
        handler.removeCallbacks(watcher)
        handler.postDelayed(watcher, 1000)
    }

    private fun stopWatching() = handler.removeCallbacks(watcher)

    /** إطفاء الشاشة يعني أنك لا تستخدم شيئاً — نوقف الصرف فوراً */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            Credit.stopSpending(this@BlockerService)
            stopWatching()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenOff) } catch (_: Exception) {}
        stopWatching()
        Credit.stopSpending(this)
        super.onDestroy()
    }

    private fun showLock() {
        val now = System.currentTimeMillis()
        if (now - lastKickAt < 700) return
        lastKickAt = now
        startActivity(Intent(this, LockActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return

        // لا نقفل على أنفسنا ولا على واجهة النظام
        if (pkg == packageName) return
        if (pkg == "com.android.systemui") return

        // الاتصال والطوارئ والإعدادات مفتوحة دائماً — لا استثناء
        if (pkg in ALWAYS_ALLOWED) return
        if (pkg.startsWith("com.android.dialer")) return
        if (pkg.startsWith("com.android.server.telecom")) return
        if (pkg.contains("emergency")) return

        // قفل الصلاة يشمل الهاتف كلّه: كل تطبيق وكل شاشة رئيسية
        if (PrayerLock.current(this) != null) {
            Credit.stopSpending(this)      // لا يُصرف رصيدك أثناء قفل الصلاة
            stopWatching()
            showLock()
            return
        }

        // ── نظام النقاط ──
        // الرصيد لا يُصرف إلا داخل تطبيق محظور. أي شاشة أخرى توقف العدّاد.
        val onBlockedApp = pkg !in LAUNCHERS && Prefs.isBlocked(this, pkg)

        if (!onBlockedApp) {
            Credit.stopSpending(this)      // خرجت — يتجمّد رصيدك كما هو
            stopWatching()
            return
        }

        if (!ChallengeLock.enabled(this)) return

        if (Credit.isEmpty(this)) {        // نفد الرصيد — اكسب غيره
            stopWatching()
            showLock()
            return
        }

        Credit.startSpending(this)         // دخلت — يبدأ العدّ
        startWatching()
    }

    override fun onInterrupt() {}
}
