package com.noor.blocker

import android.content.Context

/**
 * رصيد الاستخدام — نظام النقاط.
 *
 * الفكرة: الضغطات مع الذكر تشتري لك دقائق، والدقائق **لا تُصرف إلا
 * وأنت داخل تطبيق محظور فعلاً**. تخرج من إنستغرام فيتوقّف العدّاد
 * ويبقى رصيدك كما هو. تعود فيُستأنف. ينفد الرصيد فيُقفل حتى تكسب
 * غيره.
 *
 * لماذا لا نخزّن «الوقت المتبقّي» ونطرح منه كل ثانية؟
 * لأن الخدمة قد تُقتل، والهاتف قد ينام، فيضيع الحساب أو يُغشّ.
 * بدل ذلك نخزّن شيئين فقط:
 *   balance     : رصيد مؤكَّد بالمللي ثانية
 *   spendingSince: لحظة دخولك تطبيقاً محظوراً (٠ = لست تصرف الآن)
 * والمتبقّي يُحسب لحظياً من الفرق. لا مؤقّت يُعتمد عليه، ولا حالة
 * تضيع بموت الخدمة: أسوأ ما يحدث أن تُحتسب المدة كاملة وهذا في
 * صالح الالتزام لا ضدّه.
 */
object Credit {

    private const val BALANCE = "crBalance"
    private const val SINCE = "crSince"
    private const val PKG = "crPkg"
    private const val PER_REP = "crPerRep"
    private const val EARNED = "crEarnedTotal"
    private const val SPENT = "crSpentTotal"
    private const val APP_PREFIX = "crApp_"

    /** كم دقيقة تكسبها كل ضغطة */
    fun minutesPerRep(c: Context) = Prefs.p(c).getInt(PER_REP, 2)
    fun setMinutesPerRep(c: Context, m: Int) = Prefs.p(c).edit().putInt(PER_REP, m).apply()

    /** مكافأة الجولة كاملة = عدد الضغطات × دقائق الضغطة */
    fun rewardMinutes(c: Context) = ChallengeLock.reps(c) * minutesPerRep(c)

    private fun balance(c: Context) = Prefs.p(c).getLong(BALANCE, 0L)
    private fun spendingSince(c: Context) = Prefs.p(c).getLong(SINCE, 0L)

    /** المتبقّي الآن بالمللي ثانية — يشمل ما يُصرف في هذه اللحظة */
    fun remainingMs(c: Context): Long {
        val since = spendingSince(c)
        val b = balance(c)
        if (since == 0L) return b.coerceAtLeast(0)
        val used = System.currentTimeMillis() - since
        return (b - used).coerceAtLeast(0)
    }

    fun remainingSec(c: Context) = (remainingMs(c) / 1000).toInt()

    /** هل نحن الآن في وضع الصرف؟ */
    fun isSpending(c: Context) = spendingSince(c) != 0L

    /** أيّ تطبيق يُصرف عليه الآن */
    fun spendingPkg(c: Context): String = Prefs.p(c).getString(PKG, "") ?: ""

    /** دخل تطبيقاً محظوراً — يبدأ العدّاد */
    fun startSpending(c: Context, pkg: String = "") {
        if (spendingSince(c) != 0L) return
        Prefs.p(c).edit()
            .putLong(SINCE, System.currentTimeMillis())
            .putString(PKG, pkg)
            .apply()
    }

    /** خرج من التطبيقات المحظورة (أو أطفأ الشاشة) — يتوقّف العدّاد ويُثبَّت الرصيد */
    fun stopSpending(c: Context): Long {
        val since = spendingSince(c)
        if (since == 0L) return 0
        val used = (System.currentTimeMillis() - since).coerceAtLeast(0)
        val real = minOf(used, balance(c))          // لا نحتسب أكثر مما كان لديه
        val pkg = spendingPkg(c)
        val e = Prefs.p(c).edit()
            .putLong(BALANCE, (balance(c) - used).coerceAtLeast(0))
            .putLong(SINCE, 0L)
            .putString(PKG, "")
            .putLong(SPENT, Prefs.p(c).getLong(SPENT, 0L) + real)
            .putLong(dayKey(), Prefs.p(c).getLong(dayKey(), 0L) + real)
        if (pkg.isNotEmpty()) {
            e.putLong(APP_PREFIX + pkg, Prefs.p(c).getLong(APP_PREFIX + pkg, 0L) + real)
        }
        e.apply()
        return real
    }

    /** مفتاح إجمالي اليوم — يتغيّر مع تغيّر التاريخ فيبدأ العدّ تلقائياً */
    private fun dayKey(): String {
        val c = java.util.Calendar.getInstance()
        return String.format("crDay_%04d-%02d-%02d", c.get(java.util.Calendar.YEAR),
            c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
    }

    fun spentTodayMinutes(c: Context) = (Prefs.p(c).getLong(dayKey(), 0L) / 60_000L).toInt()

    /** كم دقيقة صُرفت على كل تطبيق — مرتّبة تنازلياً */
    fun perApp(c: Context): List<Pair<String, Int>> =
        Prefs.p(c).all.entries
            .filter { it.key.startsWith(APP_PREFIX) && it.value is Long }
            .map { it.key.removePrefix(APP_PREFIX) to ((it.value as Long) / 60_000L).toInt() }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }

    /** يمنح رصيداً يدوياً — يُسجَّل في السجلّ كي يراه صاحبه */
    fun grant(c: Context, minutes: Int) {
        stopSpending(c)
        Prefs.p(c).edit().putLong(BALANCE, (balance(c) + minutes * 60_000L).coerceAtLeast(0)).apply()
    }

    /** تصفير كامل: الرصيد والإجماليات وسجلّ التطبيقات */
    fun resetAll(c: Context) {
        val e = Prefs.p(c).edit()
        Prefs.p(c).all.keys.filter { it.startsWith(APP_PREFIX) || it.startsWith("crDay_") }
            .forEach { e.remove(it) }
        e.putLong(BALANCE, 0L).putLong(SINCE, 0L).putString(PKG, "")
            .putLong(EARNED, 0L).putLong(SPENT, 0L).apply()
    }

    /** أتمّ جولة تحدٍّ — يُضاف الرصيد */
    fun award(c: Context): Int {
        stopSpending(c)                       // نثبّت ما صُرف قبل الإضافة
        val mins = rewardMinutes(c)
        Prefs.p(c).edit()
            .putLong(BALANCE, balance(c) + mins * 60_000L)
            .putLong(EARNED, Prefs.p(c).getLong(EARNED, 0L) + mins * 60_000L)
            .apply()
        return mins
    }

    /** هل نفد الرصيد؟ */
    fun isEmpty(c: Context) = remainingMs(c) <= 0

    /** إجماليات للعرض فقط */
    fun earnedMinutes(c: Context) = (Prefs.p(c).getLong(EARNED, 0L) / 60_000L).toInt()
    fun spentMinutes(c: Context) = (Prefs.p(c).getLong(SPENT, 0L) / 60_000L).toInt()

    /** نصّ الرصيد: mm:ss */
    fun format(c: Context): String {
        val s = remainingSec(c)
        return String.format("%02d:%02d", s / 60, s % 60)
    }
}
