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

package org.matrix.android.sdk.internal.session.homeserver

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Before
import org.junit.Test
import org.matrix.android.sdk.api.auth.data.AuthMetadata
import org.matrix.android.sdk.api.auth.data.DelegatedAuthConfig
import org.matrix.android.sdk.api.auth.data.HomeServerConnectionConfig
import org.matrix.android.sdk.api.auth.data.WellKnown
import org.matrix.android.sdk.api.auth.wellknown.WellknownResult
import org.matrix.android.sdk.internal.database.model.HomeServerCapabilitiesEntity
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.session.integrationmanager.IntegrationManagerConfigExtractor
import org.matrix.android.sdk.internal.session.media.AuthenticatedMediaAPI
import org.matrix.android.sdk.internal.session.media.UnauthenticatedMediaAPI
import org.matrix.android.sdk.internal.wellknown.GetWellknownTask
import org.matrix.android.sdk.test.fakes.FakeMonarchy

class DefaultGetHomeServerCapabilitiesTaskTest {

    private val capabilitiesAPI: CapabilitiesAPI = mockk()
    private val unauthenticatedMediaAPI: UnauthenticatedMediaAPI = mockk()
    private val authenticatedMediaAPI: AuthenticatedMediaAPI = mockk()
    private val monarchy = FakeMonarchy()
    private val globalErrorReceiver: GlobalErrorReceiver = mockk(relaxed = true)
    private val getWellknownTask: GetWellknownTask = mockk()
    private val configExtractor: IntegrationManagerConfigExtractor = mockk()
    private val homeServerConnectionConfig: HomeServerConnectionConfig = mockk()
    private val authMetadataAPI: AuthMetadataAPI = mockk()

    private val task = DefaultGetHomeServerCapabilitiesTask(
            capabilitiesAPI,
            unauthenticatedMediaAPI,
            authenticatedMediaAPI,
            monarchy.instance,
            globalErrorReceiver,
            getWellknownTask,
            configExtractor,
            homeServerConnectionConfig,
            "userId",
            authMetadataAPI
    )

    private val homeServerCapabilitiesEntity = HomeServerCapabilitiesEntity()

    @Before
    fun setUp() {
        coEvery { configExtractor.extract(any()) } returns null
        monarchy.givenWhereReturns(result = homeServerCapabilitiesEntity)
    }

    private val wellKnownWithoutDelegation = WellknownResult.Prompt(
            homeServerUrl = "https://test",
            identityServerUrl = null,
            wellKnown = WellKnown(
                    homeServer = mockk(),
                    identityServer = mockk(),
                    unstableDelegatedAuthConfig = null,
            )
    )

    private val wellKnownWithDelegationToTest1 = WellknownResult.Prompt(
            homeServerUrl = "https://test",
            identityServerUrl = null,
            wellKnown = WellKnown(
                    homeServer = mockk(),
                    identityServer = mockk(),
                    unstableDelegatedAuthConfig = DelegatedAuthConfig(
                            issuer = "https://test1",
                            accountManagementUrl = "https://test1/account"
                    )
            )
    )

    private val authMetadataForTest2 = AuthMetadata(
            issuer = "https://test2",
            accountManagementUri = "https://test2/account",
            accountManagementActionsSupported = listOf("org.matrix.device_delete", "org.matrix.profile")
    )

    private val authMetadataForTest2WithoutAccountManagement = AuthMetadata(
            issuer = "https://test2",
            accountManagementUri = null,
            accountManagementActionsSupported = null
    )

    @Test
    fun `given no OAuth config, when execute, then fields are null`() = runTest {
        // Given
        coEvery { getWellknownTask.execute(any()) } throws Exception()
        coEvery { authMetadataAPI.getAuthMetadata() } throws Exception()

        // When
        task.execute(GetHomeServerCapabilitiesTask.Params(forceRefresh = false))

        // Then
        homeServerCapabilitiesEntity.authenticationIssuer shouldBeEqualTo null
        homeServerCapabilitiesEntity.externalAccountManagementUrl shouldBeEqualTo null
        homeServerCapabilitiesEntity.externalAccountManagementSupportedActions shouldBeEqualTo null
    }

    @Test
    fun `given well-known, when execute, populates fields`() = runTest {
        // Given
        coEvery { getWellknownTask.execute(any()) } returns wellKnownWithDelegationToTest1
        coEvery { authMetadataAPI.getAuthMetadata() } throws Exception()

        // When
        task.execute(GetHomeServerCapabilitiesTask.Params(forceRefresh = false))

        // Then
        homeServerCapabilitiesEntity.authenticationIssuer shouldBeEqualTo "https://test1"
        homeServerCapabilitiesEntity.externalAccountManagementUrl shouldBeEqualTo "https://test1/account"
        homeServerCapabilitiesEntity.externalAccountManagementSupportedActions shouldBeEqualTo null
    }

    @Test
    fun `given auth metadata, when execute, populates fields`() = runTest {
        // Given
        coEvery { getWellknownTask.execute(any()) } returns wellKnownWithoutDelegation
        coEvery { authMetadataAPI.getAuthMetadata() } returns authMetadataForTest2

        // When
        task.execute(GetHomeServerCapabilitiesTask.Params(forceRefresh = false))

        // Then
        homeServerCapabilitiesEntity.authenticationIssuer shouldBeEqualTo "https://test2"
        homeServerCapabilitiesEntity.externalAccountManagementUrl shouldBeEqualTo "https://test2/account"
        homeServerCapabilitiesEntity.externalAccountManagementSupportedActions shouldBeEqualTo "org.matrix.device_delete,org.matrix.profile"
    }

    @Test
    fun `given well-known and auth metadata, when execute, treats auth metadata as authoritative`() = runTest {
        // Given
        coEvery { getWellknownTask.execute(any()) } returns wellKnownWithDelegationToTest1
        coEvery { authMetadataAPI.getAuthMetadata() } returns authMetadataForTest2WithoutAccountManagement

        // When
        task.execute(GetHomeServerCapabilitiesTask.Params(forceRefresh = false))

        // Then
        homeServerCapabilitiesEntity.authenticationIssuer shouldBeEqualTo "https://test2"
        homeServerCapabilitiesEntity.externalAccountManagementUrl shouldBeEqualTo null
        homeServerCapabilitiesEntity.externalAccountManagementSupportedActions shouldBeEqualTo null
    }
}
