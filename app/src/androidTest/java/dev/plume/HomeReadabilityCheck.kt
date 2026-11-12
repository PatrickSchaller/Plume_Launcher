package dev.plume

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

/** Busy artwork and history fixtures stay in the test APK. */
object HomeReadabilityCheck {
    @JvmStatic fun run(test: Instrumentation, activity: Activity): Int {
        val prefs = activity.getSharedPreferences("home", Context.MODE_PRIVATE)
        val before = prefs.all
        val changed = mutableSetOf("sort", "order", "glass_style", "last_opened_show", "last_opened_show_at")
        var checks = 0
        fun verify(condition: Boolean, message: String) { checks++; check(condition) { message } }
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun call(name: String, vararg arguments: Any?) = MainActivity::class.java.declaredMethods
            .first { it.name == name && it.parameterCount == arguments.size }.apply { isAccessible = true }.invoke(activity, *arguments)
        fun main(block: () -> Unit) {
            var error: Throwable? = null
            test.runOnMainSync { try { block() } catch (failure: Throwable) { error = failure } }
            error?.let { throw it }
            test.waitForIdleSync()
        }
        fun packages(): List<String> = (call("sortedApps") as List<*>).map { app ->
            app!!.javaClass.getDeclaredField("pkg").apply { isAccessible = true }.get(app) as String
        }
        val artwork = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)
        Canvas(artwork).apply {
            val paint = Paint()
            for (y in 0 until 540 step 36) for (x in 0 until 960 step 36) {
                paint.color = if ((x / 36 + y / 36) % 2 == 0) Color.WHITE else Color.rgb(16, 21, 30)
                drawRect(x.toFloat(), y.toFloat(), x + 36f, y + 36f, paint)
            }
        }
        try {
            var apps = emptyList<String>()
            main { apps = packages(); verify(apps.size >= 3, "Need three installed fixture apps") }
            val now = System.currentTimeMillis()
            for (pkg in apps) {
                changed += "t_$pkg"; changed += "n_$pkg"
                prefs.edit().putLong("t_$pkg", 0).putInt("n_$pkg", 0).commit()
            }
            prefs.edit().putString("sort", "recent").putLong("t_${apps[0]}", now - 1000).putInt("n_${apps[2]}", 50).commit()
            RecentActivity.opened(prefs, apps[1], now)
            main {
                verify(packages().take(2) == listOf(apps[1], apps[0]), "Recent ordering used frequency or default order")
                prefs.edit().putString("sort", "usage").commit()
                verify(packages().first() == apps[2], "Most used stopped working")
                prefs.edit().putString("sort", "manual").putString("order", apps.reversed().joinToString(",")).commit()
                verify(packages() == apps.reversed(), "Manual order stopped working")
                prefs.edit().putString("sort", "recent").commit()
            }
            val store = apps.first { activity.packageManager.getLeanbackLaunchIntentForPackage(it) != null }
            val monitor = test.addMonitor(IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER) },
                Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
            try {
                main {
                    call("startApp", activity.packageManager.getLeanbackLaunchIntentForPackage(store), null, store)
                    verify(prefs.getLong("t_$store", 0) >= now && monitor.hits == 1, "Successful app dispatch did not record recency")
                    val bad = "dev.plume.missing.fixture"
                    changed += "t_$bad"; changed += "n_$bad"
                    prefs.edit().remove("t_$bad").remove("n_$bad").commit()
                    try { call("startApp", Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(bad, "$bad.Missing"), null, bad) } catch (_: java.lang.reflect.InvocationTargetException) { }
                    verify(!prefs.contains("t_$bad"), "Failed app launch recorded a false recent use")
                }
            } finally { test.removeMonitor(monitor) }

            val progType = Class.forName("dev.plume.MainActivity\$Prog")
            val constructor = progType.declaredConstructors.first { it.parameterCount == 8 }.apply { isAccessible = true }
            val shows = listOf("Latest episode fixture", "Earlier episode fixture", "Another service fixture").mapIndexed { i, title ->
                constructor.newInstance(title, "Offline home preview", 25 + i * 15, null,
                    Intent(Intent.ACTION_MAIN).setPackage(apps[i]).toUri(Intent.URI_INTENT_SCHEME), apps[i], apps[i], true)
            }
            prefs.edit().putString("last_opened_show", "${apps[0]}/Latest episode fixture").putLong("last_opened_show_at", now).commit()
            for (style in listOf("frosted", "clear")) {
                prefs.edit().putString("glass_style", style).commit()
                main {
                    activity.findViewById<ImageView>(R.id.wallpaper).setImageBitmap(artwork)
                    field("wpThumb").set(activity, artwork)
                    field("wpFrames").set(activity, listOf(artwork))
                    field("glassPixels").set(activity, glassScreen(artwork, Color.BLACK))
                    call("applyGlass"); call("renderApps")
                    field("watchKey").set(activity, null)
                    call("showWatchNext", shows); call("fitInk")
                    val root = activity.findViewById<ViewGroup>(android.R.id.content)
                    fun badge(v: View): Boolean = v is TextView && v.text.toString() == "Last opened"
                        || v is ViewGroup && (0 until v.childCount).any { badge(v.getChildAt(it)) }
                    verify(badge(root), "Last opened show was not called out")
                    for (id in listOf(R.id.searchButton, R.id.wifi, R.id.allAppsButton, R.id.systemSettings, R.id.launcherSettings)) {
                        val icon = activity.findViewById<ImageView>(id)
                        verify(icon.background is android.graphics.drawable.GradientDrawable && icon.alpha == 1f, "Control has no stable backplate")
                        val sample = Bitmap.createBitmap(icon.width, icon.height, Bitmap.Config.ARGB_8888)
                        icon.background.draw(Canvas(sample))
                        verify(Color.alpha(sample.getPixel(sample.width / 2, sample.height / 2)) >= 0xDE, "Icon backplate is too transparent")
                        sample.recycle()
                    }
                    activity.findViewById<View>(R.id.launcherSettings).requestFocus()
                }
                test.uiAutomation.waitForIdle(250, 3000)
                ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(
                    "screencap -p /sdcard/plume-home-$style-preview.png")).use { it.readBytes() }
            }
            return checks
        } finally {
            val restore = prefs.edit()
            for (key in changed) when (val value = before[key]) {
                is String -> restore.putString(key, value)
                is Long -> restore.putLong(key, value)
                is Int -> restore.putInt(key, value)
                else -> restore.remove(key)
            }
            restore.commit()
            // The activity owns this bitmap until it closes; do not recycle pixels still on screen.
        }
    }
}
