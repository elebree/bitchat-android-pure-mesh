package com.bitchat.android.ui

import com.bitchat.android.mesh.BluetoothMeshDelegate
import com.bitchat.android.ui.NotificationTextUtils
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Handles all BluetoothMeshDelegate callbacks and routes them to appropriate managers
 */
class MeshDelegateHandler(
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val channelManager: ChannelManager,
    private val privateChatManager: PrivateChatManager,
    private val notificationManager: NotificationManager,
    private val coroutineScope: CoroutineScope,
    private val onHapticFeedback: () -> Unit,
    private val getMyPeerID: () -> String,
    private val getMeshService: () -> MeshService
) : BluetoothMeshDelegate {

    override fun didReceiveMessage(message: BitchatMessage) {
        coroutineScope.launch {
            // FIXED: Deduplicate messages from dual connection paths
            val messageKey = messageManager.generateMessageKey(message)
            if (messageManager.isMessageProcessed(messageKey)) {
                return@launch // Duplicate message, ignore
            }
            messageManager.markMessageProcessed(messageKey)

            // Check if sender is blocked
            message.senderPeerID?.let { senderPeerID ->
                if (privateChatManager.isPeerBlocked(senderPeerID)) {
                    return@launch
                }
            }

            // Trigger haptic feedback
            onHapticFeedback()

            if (message.isPrivate) {
                // Private message
                privateChatManager.handleIncomingPrivateMessage(message)

                // Reactive read receipts: if chat is focused, send immediately for this message
                message.senderPeerID?.let { senderPeerID ->
                    sendReadReceiptIfFocused(message)
                }

                // Show notification with enhanced information - now includes senderPeerID
                message.senderPeerID?.let { senderPeerID ->
                    // Use nickname if available, fall back to sender or senderPeerID
                    val senderNickname = message.sender.takeIf { it != senderPeerID } ?: senderPeerID
                    val preview = NotificationTextUtils.buildPrivateMessagePreview(message)
                    notificationManager.showPrivateMessageNotification(
                        senderPeerID = senderPeerID,
                        senderNickname = senderNickname,
                        messageContent = preview
                    )
                }
            } else if (message.channel != null) {
                // Channel message: AppStateStore is the source of truth for list; only manage unread
                if (state.getJoinedChannelsValue().contains(message.channel)) {
                    val channel = message.channel
                    if (state.getCurrentChannelValue() != channel) {
                        val currentUnread = state.getUnreadChannelMessagesValue().toMutableMap()
                        currentUnread[channel] = (currentUnread[channel] ?: 0) + 1
                        state.setUnreadChannelMessages(currentUnread)
                    }
                }
            } else {
                // Public mesh message: AppStateStore is the source of truth; avoid double-adding to UI state
                // Still run mention detection/notifications
                checkAndTriggerMeshMentionNotification(message)
            }

            // Periodic cleanup
            if (messageManager.isMessageProcessed("cleanup_check_${System.currentTimeMillis()/30000}")) {
                messageManager.cleanupDeduplicationCaches()
            }
        }
    }

    override fun didUpdatePeerList(peers: List<String>) {
        coroutineScope.launch {
            processPeerUpdate(peers.distinct())
        }
    }

    private suspend fun processPeerUpdate(mergedPeers: List<String>) {
        state.setConnectedPeers(mergedPeers)
        state.setIsConnected(mergedPeers.isNotEmpty())
        notificationManager.showActiveUserNotification(mergedPeers)

        // Flush router outbox for any peers that just connected (and their noiseHex aliases)
        runCatching { com.bitchat.android.services.MessageRouter.tryGetInstance()?.onPeersUpdated(mergedPeers) }

        // Clean up channel members who disconnected
        channelManager.cleanupDisconnectedMembers(mergedPeers, getMyPeerID())

        // Handle chat view migration based on current selection and new peer list
        state.getSelectedPrivateChatPeerValue()?.let { currentPeer ->
            val isNoiseHex = currentPeer.length == 64 && currentPeer.matches(Regex("^[0-9a-fA-F]+$"))
            val isMeshEphemeral = currentPeer.length == 16 && currentPeer.matches(Regex("^[0-9a-fA-F]+$"))

            if (isNoiseHex) {
                // Offline stable chat is open, and peer may have come online on mesh.
                // Resolve canonical target (prefer connected mesh peer if available)
                val canonical = com.bitchat.android.services.ConversationAliasResolver.resolveCanonicalPeerID(
                    selectedPeerID = currentPeer,
                    connectedPeers = mergedPeers,
                    meshNoiseKeyForPeer = { pid -> getPeerInfo(pid)?.noisePublicKey },
                    meshHasPeer = { pid -> mergedPeers.contains(pid) }
                )
                if (canonical != currentPeer) {
                    // Merge conversations and switch selection to the live mesh peer (or noiseHex)
                    com.bitchat.android.services.ConversationAliasResolver.unifyChatsIntoPeer(state, canonical, listOf(currentPeer))
                    state.setSelectedPrivateChatPeer(canonical)
                }
            } else if (isMeshEphemeral && !mergedPeers.contains(currentPeer)) {
                // Forward case: mesh chat lost connection. If a stable key exists, migrate to the noise-hex chat.
                val favoriteRel = try {
                    val info = getPeerInfo(currentPeer)
                    val noiseKey = info?.noisePublicKey
                    if (noiseKey != null) {
                        com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey)
                    } else null
                } catch (_: Exception) { null }

                if (favoriteRel?.isMutual == true) {
                    val noiseHex = favoriteRel.peerNoisePublicKey.joinToString("") { b -> "%02x".format(b) }
                    if (noiseHex != currentPeer) {
                        com.bitchat.android.services.ConversationAliasResolver.unifyChatsIntoPeer(
                            state = state,
                            targetPeerID = noiseHex,
                            keysToMerge = listOf(currentPeer)
                        )
                        state.setSelectedPrivateChatPeer(noiseHex)
                    }
                } else {
                    privateChatManager.cleanupDisconnectedPeer(currentPeer)
                }
            }
        }

        // Global unification: for each connected peer, merge any offline/stable conversation.
        mergedPeers.forEach { pid ->
            try {
                val info = getPeerInfo(pid)
                val noiseKey = info?.noisePublicKey ?: return@forEach
                val noiseHex = noiseKey.joinToString("") { b -> "%02x".format(b) }
                unifyChatsIntoPeer(pid, listOf(noiseHex))
            } catch (_: Exception) { }
        }
    }

    /**
     * Merge any chats stored under the given keys into the connected peer's chat entry.
     */
    private fun unifyChatsIntoPeer(targetPeerID: String, keysToMerge: List<String>) {
        com.bitchat.android.services.ConversationAliasResolver.unifyChatsIntoPeer(state, targetPeerID, keysToMerge)
    }

    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        coroutineScope.launch {
            channelManager.removeChannelMember(channel, fromPeer)
        }
    }

    override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
        coroutineScope.launch {
            messageManager.updateMessageDeliveryStatus(messageID, DeliveryStatus.Delivered(recipientPeerID, Date()))
        }
    }

    override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
        coroutineScope.launch {
            messageManager.updateMessageDeliveryStatus(messageID, DeliveryStatus.Read(recipientPeerID, Date()))
        }
    }

    override fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long) {
        // Handled by ChatViewModel for verification flow
    }

    override fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long) {
        // Handled by ChatViewModel for verification flow
    }

    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        return channelManager.decryptChannelMessage(encryptedContent, channel)
    }

    override fun getNickname(): String? = state.getNicknameValue()

    override fun isFavorite(peerID: String): Boolean {
        return privateChatManager.isFavorite(peerID)
    }

    /**
     * Check for mentions in mesh messages and trigger notifications
     */
    private fun checkAndTriggerMeshMentionNotification(message: BitchatMessage) {
        try {
            // Get user's current nickname
            val currentNickname = state.getNicknameValue()
            if (currentNickname.isNullOrEmpty()) {
                return
            }

            // Check if this message mentions the current user using @username format
            val isMention = checkForMeshMention(message.content, currentNickname)

            if (isMention) {
                android.util.Log.d("MeshDelegateHandler", "🔔 Triggering mesh mention notification from ${message.sender}")

                notificationManager.showMeshMentionNotification(
                    senderNickname = message.sender,
                    messageContent = message.content,
                    senderPeerID = message.senderPeerID
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("MeshDelegateHandler", "Error checking mesh mentions: ${e.message}")
        }
    }

    /**
     * Check if the content mentions the current user with @username format (simple, no hash suffix)
     */
    private fun checkForMeshMention(content: String, currentNickname: String): Boolean {
        // Simple mention pattern for mesh: @username
        val mentionPattern = "@([\\p{L}0-9_]+)".toRegex()

        return mentionPattern.findAll(content).any { match ->
            val mentionedUsername = match.groupValues[1]
            // Direct comparison for mesh mentions (no hash suffix to remove)
            mentionedUsername.equals(currentNickname, ignoreCase = true)
        }
    }

    /**
     * Send read receipts reactively based on UI focus state.
     * Uses same logic as notification system - send read receipt if user is currently
     * viewing the private chat with this sender AND app is in foreground.
     */
    private fun sendReadReceiptIfFocused(message: BitchatMessage) {
        // Get notification manager's focus state (mirror the notification logic)
        val isAppInBackground = notificationManager.getAppBackgroundState()
        val currentPrivateChatPeer = notificationManager.getCurrentPrivateChatPeer()

        // Send read receipt if user is currently focused on this specific chat
        val senderPeerID = message.senderPeerID
        val shouldSendReadReceipt = !isAppInBackground && senderPeerID != null && currentPrivateChatPeer == senderPeerID

            if (shouldSendReadReceipt) {
                android.util.Log.d("MeshDelegateHandler", "Sending reactive read receipt for focused chat with $senderPeerID (message=${message.id})")
                val nickname = state.getNicknameValue() ?: "unknown"
                val mesh = getMeshService()
                val sent = try {
                    val hasMesh = mesh.getPeerInfo(senderPeerID!!)?.isConnected == true && mesh.hasEstablishedSession(senderPeerID)
                    if (hasMesh) {
                        mesh.sendReadReceipt(message.id, senderPeerID, nickname)
                        true
                    } else {
                        false
                    }
                } catch (_: Exception) {
                    false
                }
                if (sent) {
                    // Ensure unread badge is cleared for this peer immediately
                    try {
                        val current = state.getUnreadPrivateMessagesValue().toMutableSet()
                        if (current.remove(senderPeerID)) {
                            state.setUnreadPrivateMessages(current)
                        }
                    } catch (_: Exception) { }
                }
            } else {
                android.util.Log.d("MeshDelegateHandler", "Skipping read receipt - chat not focused (background: $isAppInBackground, current peer: $currentPrivateChatPeer, sender: $senderPeerID)")
            }
        }

    // registerPeerPublicKey REMOVED - fingerprints now handled centrally in PeerManager

    /**
     * Expose mesh peer info for components that need to resolve identities.
     */
    fun getPeerInfo(peerID: String): com.bitchat.android.mesh.PeerInfo? {
        return getMeshService().getPeerInfo(peerID)
    }

}
