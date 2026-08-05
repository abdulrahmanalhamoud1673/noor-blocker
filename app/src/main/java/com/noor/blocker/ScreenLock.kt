package com.noor.blocker

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * قفل الشاشة القسريّ وقت الصلاة.
 *
 * حتى الآن كنّا نغطّي الشاشة بنافذتنا: أي تطبيق تفتحه نظهر فوقه.
 * هذا يمنع الاستخدام لكنه لا يُطفئ الشاشة. مع صلاحية «مدير الجهاز»
 * نستطيع استدعاء lockNow فيُقفل الهاتف فعلاً كما لو ضغطتَ زرّ
 * الإغلاق — سواء كان بيدك أو في جيبك.
 *
 * حدّ لا يمكن تجاوزه مهما فعلنا: **لا يستطيع أي تطبيق أن يمنعك من
 * إدخال رمز القفل.** شاشة قفل النظام ملك أندرويد، ولا سبيل إلى
 * تعطيلها إلا بجهاز مُدار من جهة عمل. لكن حين تُدخل الرمز تجد
 * شاشتنا فوق كل شيء، وأي محاولة استخدام تُعيد قفل الشاشة — فالأثر
 * العملي أنك لا تستطيع استعمال الهاتف حتى ينتهي الوقت.
 *
 * ولأن شاشة قفل النظام تبقى كما هي، تبقى **مكالمات الطوارئ متاحة
 * منها دائماً**، وهذا في مصلحتك لا ضدّها.
 */
object ScreenLock {

    private const val KEY = "forceLock"

    fun component(c: Context) = ComponentName(c, NoorDeviceAdmin::class.java)

    private fun dpm(c: Context) =
        c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    /** هل منحتَ التطبيق صلاحية مدير الجهاز؟ */
    fun isAdmin(c: Context): Boolean = try {
        dpm(c).isAdminActive(component(c))
    } catch (_: Exception) { false }

    /** هل فعّلتَ الميزة؟ (تحتاج الصلاحية أيضاً كي تعمل) */
    fun enabled(c: Context) = Prefs.p(c).getBoolean(KEY, true) && isAdmin(c)
    fun setEnabled(c: Context, b: Boolean) = Prefs.p(c).edit().putBoolean(KEY, b).apply()

    /** شاشة النظام لطلب الصلاحية */
    fun requestIntent(c: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(c))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "ليقفل نور شاشة هاتفك تلقائياً عند دخول وقت الصلاة. " +
                "الصلاحية الوحيدة المطلوبة هي قفل الشاشة — لا مسح للجهاز ولا تغيير لكلمة السر."
            )
        }

    /** يسحب الصلاحية — يحتاجه قبل حذف التطبيق */
    fun revoke(c: Context) {
        try { dpm(c).removeActiveAdmin(component(c)) } catch (_: Exception) {}
    }

    /** يقفل الشاشة الآن */
    fun lockNow(c: Context): Boolean {
        if (!enabled(c)) return false
        return try { dpm(c).lockNow(); true } catch (_: Exception) { false }
    }
}

/**
 * لا يفعل شيئاً بنفسه — وجوده شرط لمنح صلاحية مدير الجهاز.
 * نُبقيه فارغاً عمداً: كلّما قلّت الصلاحيات والسلوكيات كان أأمن.
 */
class NoorDeviceAdmin : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        EventLog.add(context, EventLog.SETTING, "فُعّل قفل الشاشة القسريّ")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        EventLog.add(context, EventLog.SETTING, "أُلغي قفل الشاشة القسريّ")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "بإلغائها لن يَقفل هاتفك تلقائياً وقت الصلاة."
}
