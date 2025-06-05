/*
 * Copyright (c) 2024 New Vector Ltd
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

package im.vector.app.features.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.Context
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import im.vector.app.R
import im.vector.app.core.platform.PendingIntentCompat
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.MainActivity
import im.vector.app.features.home.HomeActivity
import im.vector.app.features.notifications.NotificationUtils.Companion.CALL_NOTIFICATION_CHANNEL_ID
import im.vector.app.features.notifications.NotificationUtils.Companion.SILENT_NOTIFICATION_CHANNEL_ID
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.core.utils.timer.Clock
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JitsiNotificationsUtils @Inject constructor(
        private val context: Context,
        private val stringProvider: StringProvider,
        private val notificationUtils: NotificationUtils,
        private val clock: Clock,
        ) {
    /**
     * Build an incoming jitsi call notification.
     * This notification starts the VectorHomeActivity which is in charge of centralizing the incoming call flow.
     *
     * @param callId id of the jitsi call
     * @param signalingRoomId id of the room
     * @param title title of the notification
     * @param fromBg true if the app is in background when posting the notification
     * @return the call notification.
     */
    fun buildIncomingJitsiCallNotification(
            callId: String,
            signalingRoomId: String,
            title: String,
            fromBg: Boolean,
    ): Notification {
        val accentColor = ContextCompat.getColor(context, im.vector.lib.ui.styles.R.color.notification_accent_color)
        val notificationChannel = if (fromBg) CALL_NOTIFICATION_CHANNEL_ID else SILENT_NOTIFICATION_CHANNEL_ID
        val builder = NotificationCompat.Builder(context, notificationChannel)
                .setContentTitle(notificationUtils.ensureTitleNotEmpty(title))
                .apply {
                    setContentText(stringProvider.getString(CommonStrings.incoming_video_call))
                    setSmallIcon(R.drawable.ic_call_answer_video)
                }
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setColor(ThemeUtils.getColor(context, android.R.attr.colorPrimary))
                .setLights(accentColor, 500, 500)
                .setOngoing(true)
                .setDefaults(NotificationCompat.DEFAULT_SOUND)
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))

        val contentIntent = MainActivity.getCallIntent(
                context = context,
                roomId = signalingRoomId,
                callId = callId,
        )

        val contentPendingIntent = PendingIntent.getActivity(
                context,
                clock.epochMillis().toInt(),
                contentIntent,
                PendingIntentCompat.FLAG_IMMUTABLE
        )

        val answerCallPendingIntent = TaskStackBuilder.create(context)
                .addNextIntentWithParentStack(HomeActivity.newIntent(context, firstStartMainActivity = false))
                .addNextIntent(contentIntent)
                .getPendingIntent(clock.epochMillis().toInt(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntentCompat.FLAG_IMMUTABLE)

        val rejectCallPendingIntent = notificationUtils.buildRejectCallPendingIntent(callId)

        builder.addAction(
                NotificationCompat.Action(
                        IconCompat.createWithResource(context, R.drawable.ic_call_hangup)
                                .setTint(ThemeUtils.getColor(context, android.R.attr.colorError)),
                        notificationUtils.getActionText(CommonStrings.call_notification_reject, android.R.attr.colorError),
                        rejectCallPendingIntent
                )
        )

        builder.addAction(
                NotificationCompat.Action(
                        R.drawable.ic_call_answer,
                        notificationUtils.getActionText(CommonStrings.call_notification_open_app_action, android.R.attr.colorPrimary),
                        answerCallPendingIntent
                )
        )
        if (fromBg) {
            // Compat: Display the incoming call notification on the lock screen
            builder.priority = NotificationCompat.PRIORITY_HIGH
            builder.setFullScreenIntent(contentPendingIntent, true)
        }
        return builder.build()
    }
}
