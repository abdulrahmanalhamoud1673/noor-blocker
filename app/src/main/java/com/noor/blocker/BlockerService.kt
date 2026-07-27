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
/** تطبيقات لا تُحجب أبداً مهما اختار المستخدم — الاتصال والطوارئ والإعدادات */
private val NEVER_BLOCK = setOf(
    "com.android.phone",
    "com.android.incallui",
    "com.android.contacts",
    "com.android.settings",
    "com.android.emergency",
    "com.google.android.dialer",
    "com.google.android.contacts",
    "com.samsung.android.dialer",
    "com.samsung.android.incallui",
    "com.android.launcher",
    "com.android.launcher3",
    "com.google.android.apps.nexuslauncher",
    "com.sec.android.app.launcher",
    "com.miui.home"
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

        // لا نحجب الاتصال والطوارئ والإعدادات مهما كان الاختيار
        if (pkg in NEVER_BLOCK) return
        if (pkg.startsWith("com.android.dialer")) return
        if (pkg.startsWith("com.android.server.telecom")) return

        if (!Prefs.isBlocked(this, pkg)) return
        // يُحظر إمّا في وقت الصلاة أو في جولة تحدّي الاستغفار
        val prayerLock = PrayerLock.current(this) != null
        val challengeLock = ChallengeLock.active(this)
        if (!prayerLock && !challengeLock) return

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
