package com.rohitkukreja.smartplant.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.rohitkukreja.smartplant.MainActivity

/** Posts system notifications for warning/critical alerts (shown even when the app is in the background). */
class Notifier(private val context: Context) {

    companion object {
        const val CH_CRITICAL = "plant_critical"
        const val CH_WARNING = "plant_warning"
    }

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_CRITICAL, "Critical plant alerts", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Very dry soil, empty tank, sensor faults" }
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_WARNING, "Plant care reminders", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Dry soil, temperature, sunlight" }
            )
        }
    }

    private fun allowed(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun post(alert: Alert) {
        if (alert.type.severity == Severity.INFO || !allowed()) return
        val intent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val critical = alert.type.severity == Severity.CRITICAL
        val n = NotificationCompat.Builder(context, if (critical) CH_CRITICAL else CH_WARNING)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(alert.type.title)
            .setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.message))
            .setPriority(if (critical) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(alert.type.ordinal, n)
        } catch (_: SecurityException) { }
    }
}
