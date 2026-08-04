package com.noor.blocker

import android.content.Context
import java.util.Calendar

/**
 * سجلّ ما يجري في التطبيق.
 *
 * كل حدث سطر واحد: متى، وأيقونة، ونصّ. نحتفظ بآخر ٣٠٠ حدث فقط
 * فلا ينتفخ التخزين. الغرض أن ترى بعينك ما فعله التطبيق وما فعلتَه
 * أنت — بما في ذلك منحُك نفسَك رصيداً يدوياً، فذلك أصدق من إخفائه.
 *
 * نخزّنه نصّاً واحداً مفصولاً بمحرف وحدة السجلّ () بدل JSON:
 * لا مكتبة، ولا تحليل يفشل، ولا حقول تتغيّر.
 */
object EventLog {

    private const val KEY = "evLog"
    private const val MAX = 300
    // محرفا فصل السجلّات والحقول. نكتبهما برمزيهما لا حرفياً:
    // المحارف غير المطبوعة تُشوّهها المحرّرات وأدوات النسخ بصمت.
    private val SEP = 30.toChar().toString()
    private val FLD = 31.toChar().toString()

    class Entry(val at: Long, val icon: String, val text: String)

    fun add(c: Context, icon: String, text: String) {
        val line = System.currentTimeMillis().toString() + FLD + icon + FLD +
                   text.replace(SEP, " ").replace(FLD, " ")
        val cur = Prefs.p(c).getString(KEY, "") ?: ""
        val all = if (cur.isEmpty()) line else line + SEP + cur   // الأحدث أولاً
        val trimmed = all.split(SEP).take(MAX).joinToString(SEP)
        Prefs.p(c).edit().putString(KEY, trimmed).apply()
    }

    fun all(c: Context): List<Entry> {
        val raw = Prefs.p(c).getString(KEY, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(SEP).mapNotNull { line ->
            val p = line.split(FLD)
            if (p.size < 3) null
            else Entry(p[0].toLongOrNull() ?: 0L, p[1], p[2])
        }
    }

    fun clear(c: Context) = Prefs.p(c).edit().remove(KEY).apply()

    /** كم حدثاً وقع اليوم من نوع معيّن (نطابق الأيقونة) */
    fun countToday(c: Context, icon: String): Int {
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return all(c).count { it.at >= start && it.icon == icon }
    }

    /** وقت الحدث بصيغة قصيرة: اليوم ← الساعة فقط، وإلا يوم/شهر وساعة */
    fun stamp(at: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val today = Calendar.getInstance()
        val sameDay = c.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                      c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        val hm = String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        return if (sameDay) hm
               else String.format("%d/%d %s", c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, hm)
    }

    /* أيقونات موحّدة كي يمكن العدّ والتصفية عليها */
    const val EARN = "💎"
    const val SPEND = "⏳"
    const val BLOCK = "🔒"
    const val PRAYER = "🕌"
    const val ADHAN = "🔊"
    const val ADMIN = "🛠"
    const val SETTING = "⚙"
}
