package com.noor.blocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
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

        val prayerLock = PrayerLock.current(this) != null
        val challengeLock = ChallengeLock.active(this)
        if (!prayerLock && !challengeLock) return

        // قفل الصلاة يشمل الهاتف كلّه: كل تطبيق وكل شاشة رئيسية.
        // أما تحدّي الاستغفار فيبقى على التطبيقات المختارة وحدها.
        if (!prayerLock) {
            if (pkg in LAUNCHERS) return
            if (!Prefs.isBlocked(this, pkg)) return
        }

        // منع التكرار السريع
        val now = System.currentTimeMillis()
        if (now - lastKickAt < 700) return
        lastKickAt = now

        val i = Intent(this, LockActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        }
        startActivity(i)
    }

    override fun onInterrupt() {}
}
