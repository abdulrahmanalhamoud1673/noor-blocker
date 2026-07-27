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
class BlockerService : AccessibilityService() {

    private var lastKickAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return

        // لا نقفل على أنفسنا ولا على واجهة النظام
        if (pkg == packageName) return
        if (pkg == "com.android.systemui") return

        if (!Prefs.isBlocked(this, pkg)) return
        if (PrayerLock.current(this) == null) return

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
