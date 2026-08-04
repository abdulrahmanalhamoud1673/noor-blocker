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
    private const val PER_REP = "crPerRep"
    private const val EARNED = "crEarnedTotal"
    private const val SPENT = "crSpentTotal"

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

    /** دخل تطبيقاً محظوراً — يبدأ العدّاد */
    fun startSpending(c: Context) {
        if (spendingSince(c) != 0L) return
        Prefs.p(c).edit().putLong(SINCE, System.currentTimeMillis()).apply()
    }

    /** خرج من التطبيقات المحظورة (أو أطفأ الشاشة) — يتوقّف العدّاد ويُثبَّت الرصيد */
    fun stopSpending(c: Context) {
        val since = spendingSince(c)
        if (since == 0L) return
        val used = (System.currentTimeMillis() - since).coerceAtLeast(0)
        val left = (balance(c) - used).coerceAtLeast(0)
        Prefs.p(c).edit()
            .putLong(BALANCE, left)
            .putLong(SINCE, 0L)
            .putLong(SPENT, Prefs.p(c).getLong(SPENT, 0L) + minOf(used, balance(c)))
            .apply()
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
