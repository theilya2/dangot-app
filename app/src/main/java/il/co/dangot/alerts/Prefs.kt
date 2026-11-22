package il.co.dangot.alerts

import android.content.Context

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("dangot", Context.MODE_PRIVATE)

    fun tech(c: Context): String? = p(c).getString("tech", null)
    fun setTech(c: Context, t: String) = p(c).edit().putString("tech", t).apply()

    fun seen(c: Context): Set<String> = p(c).getStringSet("seen", HashSet()) ?: HashSet()
    fun isSeen(c: Context, id: String): Boolean = seen(c).contains(id)
    fun addSeen(c: Context, id: String) {
        val s = HashSet(seen(c))
        s.add(id)
        // keep only the most recent ~200 ids to avoid unbounded growth
        val trimmed = if (s.size > 200) s.toList().takeLast(200).toHashSet() else s
        p(c).edit().putStringSet("seen", trimmed).apply()
    }
}
