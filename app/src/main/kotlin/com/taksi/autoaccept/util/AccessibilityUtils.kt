package com.taksi.autoaccept.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import com.taksi.autoaccept.service.RideAcceptAccessibilityService

object AccessibilityUtils {

    /** Erisilebilirlik servisi sistem ayarlarindan acilmis mi? */
    fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, RideAcceptAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (entry in splitter) {
            if (ComponentName.unflattenFromString(entry) == expected) return true
        }
        return false
    }

    /** Kullaniciyi erisilebilirlik ayarlarina goturur. */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
