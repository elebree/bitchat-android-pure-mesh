package com.bitchat.android.services

import android.content.Context
import android.util.Log
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.ReadReceipt

/**
 * Routes private messaging through local mesh transports.
 */
class MessageRouter private constructor(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private var mesh: MeshService
) {
    companion object {
        private const val TAG = "MessageRouter"
        @Volatile private var INSTANCE: MessageRouter? = null

        fun tryGetInstance(): MessageRouter? = INSTANCE

        fun getInstance(context: Context, mesh: MeshService): MessageRouter {
            val instance = INSTANCE ?: synchronized(this) {
                INSTANCE ?: MessageRouter(context.applicationContext, mesh).also { INSTANCE = it }
            }
            instance.mesh = mesh
            return instance
        }
    }

    private val outbox = mutableMapOf<String, MutableList<Triple<String, String, String>>>()

    fun sendPrivate(content: String, toPeerID: String, recipientNickname: String, messageID: String) {
        val directPeer = resolveMeshPeerForNoiseHex(toPeerID) ?: toPeerID
        val hasMesh = mesh.getPeerInfo(directPeer)?.isConnected == true
        val hasEstablished = mesh.hasEstablishedSession(directPeer)
        if (hasMesh && hasEstablished) {
            Log.d(TAG, "Routing PM via mesh to $directPeer msg_id=${messageID.take(8)}")
            mesh.sendPrivateMessage(content, directPeer, recipientNickname, messageID)
        } else {
            Log.d(TAG, "Queued PM for $toPeerID until mesh session is ready msg_id=${messageID.take(8)}")
            outbox.getOrPut(toPeerID) { mutableListOf() }
                .add(Triple(content, recipientNickname, messageID))
            try {
                mesh.initiateNoiseHandshake(directPeer)
            } catch (_: Exception) { }
        }
    }

    fun sendReadReceipt(receipt: ReadReceipt, toPeerID: String) {
        val directPeer = resolveMeshPeerForNoiseHex(toPeerID) ?: toPeerID
        if ((mesh.getPeerInfo(directPeer)?.isConnected == true) && mesh.hasEstablishedSession(directPeer)) {
            mesh.sendReadReceipt(
                receipt.originalMessageID,
                directPeer,
                mesh.getPeerNicknames()[directPeer] ?: mesh.myPeerID
            )
        }
    }

    fun sendDeliveryAck(messageID: String, toPeerID: String) {
        Log.d(TAG, "Delivery ACKs are handled by the mesh service for $toPeerID msg_id=${messageID.take(8)}")
    }

    fun sendFavoriteNotification(toPeerID: String, isFavorite: Boolean) {
        val directPeer = resolveMeshPeerForNoiseHex(toPeerID) ?: toPeerID
        if (mesh.getPeerInfo(directPeer)?.isConnected == true && mesh.hasEstablishedSession(directPeer)) {
            val content = if (isFavorite) "[FAVORITED]:" else "[UNFAVORITED]:"
            val nickname = mesh.getPeerNicknames()[directPeer] ?: directPeer
            mesh.sendPrivateMessage(content, directPeer, nickname)
        }
    }

    fun flushOutboxFor(peerID: String) {
        val queued = outbox[peerID] ?: return
        val iterator = queued.iterator()
        while (iterator.hasNext()) {
            val (content, nickname, messageID) = iterator.next()
            val directPeer = resolveMeshPeerForNoiseHex(peerID) ?: peerID
            val hasMesh = mesh.getPeerInfo(directPeer)?.isConnected == true
            val hasEstablished = mesh.hasEstablishedSession(directPeer)
            if (hasMesh && hasEstablished) {
                mesh.sendPrivateMessage(content, directPeer, nickname, messageID)
                iterator.remove()
            }
        }
        if (queued.isEmpty()) {
            outbox.remove(peerID)
        }
    }

    fun flushAllOutbox() {
        outbox.keys.toList().forEach { flushOutboxFor(it) }
    }

    fun onPeersUpdated(peers: List<String>) {
        peers.forEach { pid ->
            flushOutboxFor(pid)
            val noiseHex = try {
                mesh.getPeerInfo(pid)?.noisePublicKey?.joinToString("") { b -> "%02x".format(b) }
            } catch (_: Exception) {
                null
            }
            noiseHex?.let { flushOutboxFor(it) }
        }
    }

    fun onSessionEstablished(peerID: String) {
        flushOutboxFor(peerID)
        val noiseHex = try {
            mesh.getPeerInfo(peerID)?.noisePublicKey?.joinToString("") { b -> "%02x".format(b) }
        } catch (_: Exception) {
            null
        }
        noiseHex?.let { flushOutboxFor(it) }
    }

    private fun resolveMeshPeerForNoiseHex(noiseHex: String): String? {
        if (noiseHex.length != 64 || !noiseHex.matches(Regex("^[0-9a-fA-F]+$"))) return null
        return try {
            mesh.getPeerNicknames().keys.firstOrNull { pid ->
                val keyHex = mesh.getPeerInfo(pid)?.noisePublicKey?.joinToString("") { b -> "%02x".format(b) }
                keyHex != null && keyHex.equals(noiseHex, ignoreCase = true)
            }
        } catch (_: Exception) {
            null
        }
    }
}
