package com.example

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class TaskReminderReceiver : BroadcastReceiver() {

  companion object {
    const val CHANNEL_ID = "kelasku_task_reminders"
    const val CHANNEL_NAME = "Pengingat Tugas H-1"
    const val EXTRA_TASK_ID = "extra_task_id"
    const val EXTRA_TITLE = "extra_title"
    const val EXTRA_SUBJECT = "extra_subject"
    const val EXTRA_DUE_DATE = "extra_due_date"
    const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

    fun ensureChannel(context: Context) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
          val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
          ).apply {
            description = "Notifikasi pengingat H-1 sebelum tenggat tugas KelasKu."
            enableVibration(true)
          }
          manager.createNotificationChannel(channel)
        }
      }
    }

    fun showNotification(
      context: Context,
      notificationId: Int,
      title: String,
      subject: String,
      dueDate: String
    ) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val granted = ContextCompat.checkSelfPermission(
          context,
          Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
      }

      ensureChannel(context)

      val launchIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra("open_tab", "tugas")
      }
      val pendingIntent = PendingIntent.getActivity(
        context,
        notificationId,
        launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )

      val bodyText = if (subject.isNotBlank()) {
        "Tenggat besok ($dueDate) • $subject. Segera selesaikan di KelasKu!"
      } else {
        "Tenggat tugas jatuh tempo pada $dueDate. Segera cek di KelasKu!"
      }

      val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification_reminder)
        .setContentTitle("Pengingat H-1: $title")
        .setContentText(bodyText)
        .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)
        .build()

      NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
  }

  override fun onReceive(context: Context, intent: Intent) {
    val title = intent.getStringExtra(EXTRA_TITLE) ?: "Tugas KelasKu"
    val subject = intent.getStringExtra(EXTRA_SUBJECT) ?: ""
    val dueDate = intent.getStringExtra(EXTRA_DUE_DATE) ?: "Besok"
    val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, title.hashCode())
    showNotification(context, notifId, title, subject, dueDate)
  }
}
