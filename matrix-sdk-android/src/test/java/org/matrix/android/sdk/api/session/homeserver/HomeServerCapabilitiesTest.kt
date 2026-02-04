/*
 * Copyright 2026 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.api.session.homeserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeServerCapabilitiesTest {

    private val deviceId = "TEST_DEVICE_ID"

    @Test
    fun `given externalAccountManagementUrl is null, when getLogoutDeviceURL, then return null`() {
        val capabilities = HomeServerCapabilities(
                externalAccountManagementUrl = null
        )
        assertNull(capabilities.getLogoutDeviceURL(deviceId))
    }

    @Test
    fun `given supported actions is null, when getLogoutDeviceURL, then uses default stable action`() {
        val capabilities = HomeServerCapabilities(
                externalAccountManagementUrl = "https://example.com",
                externalAccountManagementSupportedActions = null
        )
        val expectedUrl = "https://example.com?action=org.matrix.device_delete&device_id=$deviceId"
        assertEquals(expectedUrl, capabilities.getLogoutDeviceURL(deviceId))
    }

    @Test
    fun `given supported actions contains stable action, when getLogoutDeviceURL, then uses stable action`() {
        val capabilities = HomeServerCapabilities(
                externalAccountManagementUrl = "https://example.com",
                externalAccountManagementSupportedActions = listOf("org.matrix.device_delete", "org.matrix.session_end", "session_end")
        )
        val expectedUrl = "https://example.com?action=org.matrix.device_delete&device_id=$deviceId"
        assertEquals(expectedUrl, capabilities.getLogoutDeviceURL(deviceId))
    }

    @Test
    fun `given supported actions contains unstable action, when getLogoutDeviceURL, then uses unstable action`() {
        val capabilities = HomeServerCapabilities(
                externalAccountManagementUrl = "https://example.com",
                externalAccountManagementSupportedActions = listOf("org.matrix.session_end", "session_end")
        )
        val expectedUrl = "https://example.com?action=org.matrix.session_end&device_id=$deviceId"
        assertEquals(expectedUrl, capabilities.getLogoutDeviceURL(deviceId))
    }

    @Test
    fun `given supported actions contains legacy action, when getLogoutDeviceURL, then uses legacy action`() {
        val capabilities = HomeServerCapabilities(
                externalAccountManagementUrl = "https://example.com",
                externalAccountManagementSupportedActions = listOf("session_end")
        )
        val expectedUrl = "https://example.com?action=session_end&device_id=$deviceId"
        assertEquals(expectedUrl, capabilities.getLogoutDeviceURL(deviceId))
    }

    @Test
    fun `given url with trailing slash, when getLogoutDeviceURL, then slash is removed`() {
        val capabilities = HomeServerCapabilities(
                externalAccountManagementUrl = "https://example.com/account/"
        )
        val expectedUrl = "https://example.com/account?action=org.matrix.device_delete&device_id=$deviceId"
        assertEquals(expectedUrl, capabilities.getLogoutDeviceURL(deviceId))
    }
}
