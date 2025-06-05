/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE in the repository root for full details.
 */

package im.vector.app.test.fakes

import android.app.Notification
import im.vector.app.features.notifications.InviteNotifiableEvent
import im.vector.app.features.notifications.JitsiNotificationsUtils
import im.vector.app.features.notifications.NotificationUtils
import im.vector.app.features.notifications.SimpleNotifiableEvent
import io.mockk.every
import io.mockk.mockk

class FakeJitsiNotificationUtils {

    val instance = mockk<JitsiNotificationsUtils>()
}
