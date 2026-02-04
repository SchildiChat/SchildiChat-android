/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import im.vector.app.core.debug.DebugNavigator
import im.vector.app.core.debug.DebugReceiver
import im.vector.app.core.debug.LeakDetector

@InstallIn(SingletonComponent::class)
@Module
object DebugModule {

    @Provides
    fun providesDebugNavigator() = object : DebugNavigator {
        override fun openDebugMenu(context: Context) {
            // no op
        }
    }

    @Provides
    fun providesDebugReceiver() = object : DebugReceiver {
        override fun register(context: Context) {
            // no op
        }

        override fun unregister(context: Context) {
            // no op
        }
    }

    @Provides
    fun providesLeakDetector() = object : LeakDetector {
        override fun enable(enable: Boolean) {
            // no op
        }
    }
}
