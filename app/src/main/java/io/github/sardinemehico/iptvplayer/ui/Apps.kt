package io.github.sardinemehico.iptvplayer.ui

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import io.github.sardinemehico.iptvplayer.MainActivity
import io.github.sardinemehico.iptvplayer.R

/** An installed app the home screen can open. [banner] is the TV banner (16:9) if it has one. */
class LaunchableApp(val pkg: String, val label: String, val icon: Drawable?, val banner: Drawable?)

/** Installed-app lookups for the home screen's app slots and the "All apps" list. Disk work: call off the UI thread. */
object Apps {

    /** Every app with a TV or phone launcher entry, except this one, sorted by name. */
    fun list(context: Context): List<LaunchableApp> {
        val pm = context.packageManager
        val seen = HashSet<String>()
        val out = ArrayList<LaunchableApp>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName || !seen.add(pkg)) continue
                out += LaunchableApp(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm), null)
            }
        }
        out.sortBy { it.label.lowercase() }
        return out
    }

    /** One app with its banner (preferred on the home screen) and icon, or null if it is gone. */
    fun info(context: Context, pkg: String): LaunchableApp? {
        val pm = context.packageManager
        return try {
            val app = pm.getApplicationInfo(pkg, 0)
            val leanback = pm.getLeanbackLaunchIntentForPackage(pkg)?.let { pm.resolveActivity(it, 0) }
            val banner = leanback?.activityInfo?.loadBanner(pm) ?: app.loadBanner(pm)
            LaunchableApp(pkg, app.loadLabel(pm).toString(), app.loadIcon(pm), banner)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun launch(activity: MainActivity, pkg: String) {
        val pm = activity.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            activity.toast(activity.getString(R.string.app_missing))
            return
        }
        try {
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            activity.toast(activity.getString(R.string.app_missing))
        }
    }

    /** A list dialog of [apps] with their icons; [onPick] gets the chosen one. */
    fun pick(activity: MainActivity, title: Int, apps: List<LaunchableApp>, onPick: (LaunchableApp) -> Unit) {
        val size = (32 * activity.resources.displayMetrics.density).toInt()
        val pad = (12 * activity.resources.displayMetrics.density).toInt()
        val adapter = object : ArrayAdapter<LaunchableApp>(activity, android.R.layout.simple_list_item_1, apps) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                val app = apps[position]
                v.text = app.label
                v.compoundDrawablePadding = pad
                val icon = app.icon?.constantState?.newDrawable()?.mutate()?.also { it.setBounds(0, 0, size, size) }
                v.setCompoundDrawablesRelative(icon, null, null, null)
                return v
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setAdapter(adapter) { _, which -> onPick(apps[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
