package com.taksi.autoaccept.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.util.Locale

data class InstalledApp(val packageName: String, val label: String)

object InstalledApps {

    /** Baslatilabilir kullanici uygulamalarini, adlarina gore sirali dondurur. */
    fun launchable(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val tr = Locale.forLanguageTag("tr")
        return pm.queryIntentActivities(intent, 0)
            .asSequence()
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter { it.packageName != context.packageName }
            .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString()) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(tr) }
            .toList()
    }

    fun labelFor(context: Context, packageName: String): String = runCatching {
        val pm: PackageManager = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}
