package dev.plume

import android.content.SharedPreferences

/** shortcut: records launches through Plume; broader app history would need Android usage access. */
object RecentActivity {
    @JvmStatic fun opened(prefs: SharedPreferences, pkg: String?, now: Long) {
        if (pkg == null) return
        prefs.edit().putLong("t_$pkg", now).putInt("n_$pkg", prefs.getInt("n_$pkg", 0) + 1).apply()
    }
}
