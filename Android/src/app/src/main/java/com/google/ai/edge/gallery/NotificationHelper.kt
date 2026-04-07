/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.atomic.AtomicBoolean

private const val RESPONSE_NOTIFICATION_CHANNEL_ID = "gallery_response_channel"
private const val RESPONSE_NOTIFICATION_ID = 1001
private val responseChannelCreated = AtomicBoolean(false)

/** Shows a system notification containing a snippet of the AI-generated response. */
fun showResponseNotification(context: Context, responseSnippet: String) {
  val notificationManager =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  if (!responseChannelCreated.getAndSet(true)) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
        NotificationChannel(
          RESPONSE_NOTIFICATION_CHANNEL_ID,
          context.getString(R.string.response_notification_channel_name),
          NotificationManager.IMPORTANCE_DEFAULT,
        )
      notificationManager.createNotificationChannel(channel)
    }
  }

  val intent =
    Intent(context, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
  val pendingIntent =
    PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

  val truncated =
    if (responseSnippet.length > 200) responseSnippet.take(200) + "…" else responseSnippet

  val notification =
    NotificationCompat.Builder(context, RESPONSE_NOTIFICATION_CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle(context.getString(R.string.response_notification_title))
      .setContentText(truncated)
      .setStyle(NotificationCompat.BigTextStyle().bigText(truncated))
      .setAutoCancel(true)
      .setContentIntent(pendingIntent)
      .setPriority(NotificationCompat.PRIORITY_DEFAULT)
      .build()

  NotificationManagerCompat.from(context).notify(RESPONSE_NOTIFICATION_ID, notification)
}
