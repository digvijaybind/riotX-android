/*
 * Copyright (c) 2020 New Vector Ltd
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

package im.vector.matrix.android.internal.session.room.create

import im.vector.matrix.android.api.session.crypto.crosssigning.CrossSigningService
import im.vector.matrix.android.api.session.events.model.Event
import im.vector.matrix.android.api.session.events.model.EventType
import im.vector.matrix.android.api.session.events.model.toContent
import im.vector.matrix.android.api.session.identity.IdentityServiceError
import im.vector.matrix.android.api.session.identity.toMedium
import im.vector.matrix.android.api.session.room.model.create.CreateRoomParams
import im.vector.matrix.android.internal.crypto.DeviceListManager
import im.vector.matrix.android.internal.crypto.MXCRYPTO_ALGORITHM_MEGOLM
import im.vector.matrix.android.internal.di.AuthenticatedIdentity
import im.vector.matrix.android.internal.network.token.AccessTokenProvider
import im.vector.matrix.android.internal.session.identity.EnsureIdentityTokenTask
import im.vector.matrix.android.internal.session.identity.data.IdentityStore
import im.vector.matrix.android.internal.session.identity.data.getIdentityServerUrlWithoutProtocol
import im.vector.matrix.android.internal.session.room.membership.threepid.ThreePidInviteBody
import java.security.InvalidParameterException
import javax.inject.Inject

internal class CreateRoomBodyBuilder @Inject constructor(
        private val ensureIdentityTokenTask: EnsureIdentityTokenTask,
        private val crossSigningService: CrossSigningService,
        private val deviceListManager: DeviceListManager,
        private val identityStore: IdentityStore,
        @AuthenticatedIdentity
        private val accessTokenProvider: AccessTokenProvider
) {

    suspend fun build(params: CreateRoomParams): CreateRoomBody {
        val invite3pids = params.invite3pids
                .takeIf { it.isNotEmpty() }
                ?.let { invites ->
                    // This can throw Exception if Identity server is not configured
                    ensureIdentityTokenTask.execute(Unit)

                    val identityServerUrlWithoutProtocol = identityStore.getIdentityServerUrlWithoutProtocol()
                            ?: throw IdentityServiceError.NoIdentityServerConfigured
                    val identityServerAccessToken = accessTokenProvider.getToken() ?: throw IdentityServiceError.NoIdentityServerConfigured

                    invites.map {
                        ThreePidInviteBody(
                                id_server = identityServerUrlWithoutProtocol,
                                id_access_token = identityServerAccessToken,
                                medium = it.toMedium(),
                                address = it.value
                        )
                    }
                }

        val initialStates = listOfNotNull(
                buildEncryptionWithAlgorithmEvent(params),
                buildHistoryVisibilityEvent(params)
        )
                .takeIf { it.isNotEmpty() }

        return CreateRoomBody(
                visibility = params.visibility,
                roomAliasName = params.roomAliasName,
                name = params.name,
                topic = params.topic,
                invitedUserIds = params.invitedUserIds,
                invite3pids = invite3pids,
                creationContent = params.creationContent,
                initialStates = initialStates,
                preset = params.preset,
                isDirect = params.isDirect,
                powerLevelContentOverride = params.powerLevelContentOverride
        )
    }

    private fun buildHistoryVisibilityEvent(params: CreateRoomParams): Event? {
        return params.historyVisibility
                ?.let {
                    val contentMap = mapOf("history_visibility" to it)

                    Event(
                            type = EventType.STATE_ROOM_HISTORY_VISIBILITY,
                            stateKey = "",
                            content = contentMap.toContent())
                }
    }

    /**
     * Add the crypto algorithm to the room creation parameters.
     */
    private suspend fun buildEncryptionWithAlgorithmEvent(params: CreateRoomParams): Event? {
        if (params.algorithm == null
                && canEnableEncryption(params)) {
            // Enable the encryption
            params.enableEncryption()
        }
        return params.algorithm
                ?.let {
                    if (it != MXCRYPTO_ALGORITHM_MEGOLM) {
                        throw InvalidParameterException("Unsupported algorithm: $it")
                    }
                    val contentMap = mapOf("algorithm" to it)

                    Event(
                            type = EventType.STATE_ROOM_ENCRYPTION,
                            stateKey = "",
                            content = contentMap.toContent()
                    )
                }
    }

    private suspend fun canEnableEncryption(params: CreateRoomParams): Boolean {
        return (params.enableEncryptionIfInvitedUsersSupportIt
                && crossSigningService.isCrossSigningVerified()
                && params.invite3pids.isEmpty())
                && params.invitedUserIds.isNotEmpty()
                && params.invitedUserIds.let { userIds ->
            val keys = deviceListManager.downloadKeys(userIds, forceDownload = false)

            userIds.all { userId ->
                keys.map[userId].let { deviceMap ->
                    if (deviceMap.isNullOrEmpty()) {
                        // A user has no device, so do not enable encryption
                        false
                    } else {
                        // Check that every user's device have at least one key
                        deviceMap.values.all { !it.keys.isNullOrEmpty() }
                    }
                }
            }
        }
    }
}
