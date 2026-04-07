package com.google.ai.edge.gallery

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

internal object ShareImageNotificationHelper {
  private const val CHANNEL_ID = "shared_image_analysis"
  private const val CHANNEL_NAME = "Shared image analysis"

  fun showProcessing(context: Context, notificationId: Int, modelName: String) {
    notify(
      context = context,
      notificationId = notificationId,
      title = context.getString(R.string.share_image_notification_processing_title),
      text =
        context.getString(R.string.share_image_notification_processing_content).format(modelName),
      ongoing = true,
    )
  }

  fun showResult(context: Context, notificationId: Int, text: String) {
    notify(
      context = context,
      notificationId = notificationId,
      title = context.getString(R.string.share_image_notification_result_title),
      text = text,
    )
  }

  fun showError(context: Context, notificationId: Int, text: String) {
    notify(
      context = context,
      notificationId = notificationId,
      title = context.getString(R.string.share_image_notification_error_title),
      text = text,
    )
  }

  private fun notify(
    context: Context,
    notificationId: Int,
    title: String,
    text: String,
    ongoing: Boolean = false,
  ) {
    if (
      ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
      return
    }

    val notificationManager =
      context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH),
    )

    val launchIntent =
      context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      } ?: Intent(context, MainActivity::class.java)

    val pendingIntent =
      PendingIntent.getActivity(
        context,
        notificationId,
        launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val builder =
      NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(pendingIntent)
        .setAutoCancel(!ongoing)
        .setOngoing(ongoing)

    NotificationManagerCompat.from(context).notify(notificationId, builder.build())
  }
}
