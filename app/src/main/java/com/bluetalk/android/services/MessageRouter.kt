package com.bluetalk.android.services

import android.content.Context
import android.util.Log
import com.bluetalk.android.mesh.BluetoothMeshService
import com.bluetalk.android.model.ReadReceipt
import com.bluetalk.android.nostr.NostrTransport

/**
 * MessageRouter: "Bộ điều hướng" tin nhắn.
 * Lớp này chịu trách nhiệm chọn con đường tối ưu để gửi tin nhắn: 
 * qua Bluetooth Mesh (ngoại tuyến) hoặc qua Nostr Transport (trực tuyến).
 */
class MessageRouter private constructor(
    private val context: Context,
    private var mesh: BluetoothMeshService,
    private val nostr: NostrTransport
) {
    companion object {
        private const val TAG = "MessageRouter"
        @Volatile private var INSTANCE: MessageRouter? = null
        fun tryGetInstance(): MessageRouter? = INSTANCE
        fun getInstance(context: Context, mesh: BluetoothMeshService): MessageRouter {
            val instance = INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val nostr = NostrTransport.getInstance(context)
                    MessageRouter(context.applicationContext, mesh, nostr).also { instance ->
                        // Đăng ký lắng nghe thay đổi danh sách yêu thích để gửi tin nhắn đang chờ.
                        try {
                            com.bluetalk.android.favorites.FavoritesPersistenceService.shared.addListener(instance.favoriteListener)
                        } catch (_: Exception) {}
                        INSTANCE = instance
                    }
                }
            }
            // Luôn cập nhật tham chiếu mesh và đồng bộ peer ID.
            instance.mesh = mesh
            instance.nostr.senderPeerID = mesh.myPeerID
            return instance
        }
    }

    // Hàng chờ gửi (Outbox): peerID -> danh sách tin nhắn (content, nickname, messageID) đang chờ được gửi.
    private val outbox = mutableMapOf<String, MutableList<Triple<String, String, String>>>()

    // Listener lắng nghe thay đổi danh sách yêu thích để giải phóng hàng chờ gửi.
    private val favoriteListener = object: com.bluetalk.android.favorites.FavoritesChangeListener {

        override fun onFavoriteChanged(noiseKeyHex: String) {
            flushOutboxFor(noiseKeyHex)
            val shortId = noiseKeyHex.take(16)
            flushOutboxFor(shortId)
        }
        override fun onAllCleared() {}
    }
    /**
     * Hàm gửi tin nhắn riêng tư (Private Message). Logic điều hướng chính:
     * 1. Nếu người nhận đang ở gần (Bluetooth) và đã bảo mật (Noise), gửi qua Mesh.
     * 2. Nếu không ở gần nhưng có định danh Nostr (Internet), gửi qua mạng Nostr thông qua Tor.
     * 3. Nếu chưa có con đường nào, lưu tin nhắn vào Outbox để chờ gửi sau.
     */
    fun sendPrivate(content: String, toPeerID: String, recipientNickname: String, messageID: String) {
        // Kiểm tra xem đây có phải là tin nhắn gửi đến một khu vực Geohash nhất định không.
        if (com.bluetalk.android.nostr.GeohashAliasRegistry.contains(toPeerID)) {
            Log.d(TAG, "Routing PM via Nostr (geohash) to alias ${toPeerID.take(12)}… id=${messageID.take(8)}…")
            val recipientHex = com.bluetalk.android.nostr.GeohashAliasRegistry.get(toPeerID)
            if (recipientHex != null) {
                val sourceGeohash = com.bluetalk.android.nostr.GeohashConversationRegistry.get(toPeerID)
                nostr.sendPrivateMessageGeohash(content, recipientHex, messageID, sourceGeohash)
                return
            }
        }

        // Kiểm tra kết nối Bluetooth hiện tại.
        val hasMesh = mesh.getPeerInfo(toPeerID)?.isConnected == true
        val hasEstablished = mesh.hasEstablishedSession(toPeerID)
        
        if (hasMesh && hasEstablished) {
            // Đường đi 1: Bluetooth Mesh (Offline).
            Log.d(TAG, "Routing PM via mesh to ${toPeerID} msg_id=${messageID.take(8)}…")
            mesh.sendPrivateMessage(content, toPeerID, recipientNickname, messageID)
        } else if (canSendViaNostr(toPeerID)) {
            // Đường đi 2: Nostr (Online) qua Tor.
            Log.d(TAG, "Routing PM via Nostr to ${toPeerID.take(32)}… msg_id=${messageID.take(8)}…")
            nostr.sendPrivateMessage(content, toPeerID, recipientNickname, messageID)
        } else {
            // Đường đi 3: Tạm thời lưu vào hàng chờ và bắt đầu quá trình trao đổi khóa Bluetooth.
            Log.d(TAG, "Queued PM for ${toPeerID} (no mesh, no Nostr mapping) msg_id=${messageID.take(8)}…")
            val q = outbox.getOrPut(toPeerID) { mutableListOf() }
            q.add(Triple(content, recipientNickname, messageID))
            Log.d(TAG, "Initiating noise handshake after queueing PM for ${toPeerID.take(8)}…")
            mesh.initiateNoiseHandshake(toPeerID)
        }
    }

    fun sendReadReceipt(receipt: ReadReceipt, toPeerID: String) {
        if ((mesh.getPeerInfo(toPeerID)?.isConnected == true) && mesh.hasEstablishedSession(toPeerID)) {
            Log.d(TAG, "Routing READ via mesh to ${toPeerID.take(8)}… id=${receipt.originalMessageID.take(8)}…")
            mesh.sendReadReceipt(receipt.originalMessageID, toPeerID, mesh.getPeerNicknames()[toPeerID] ?: mesh.myPeerID)
        } else {
            Log.d(TAG, "Routing READ via Nostr to ${toPeerID.take(8)}… id=${receipt.originalMessageID.take(8)}…")
            nostr.sendReadReceipt(receipt, toPeerID)
        }
    }

    fun sendDeliveryAck(messageID: String, toPeerID: String) {
        // Mesh delivery ACKs are sent by the receiver automatically.
        // Only route via Nostr when mesh path isn't available or when this is a geohash alias
        if (com.bluetalk.android.nostr.GeohashAliasRegistry.contains(toPeerID)) {
            val recipientHex = com.bluetalk.android.nostr.GeohashAliasRegistry.get(toPeerID)
            if (recipientHex != null) {
                nostr.sendDeliveryAckGeohash(messageID, recipientHex, try { com.bluetalk.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(context)!! } catch (_: Exception) { return })
                return
            }
        }
        if (!((mesh.getPeerInfo(toPeerID)?.isConnected == true) && mesh.hasEstablishedSession(toPeerID))) {
            nostr.sendDeliveryAck(messageID, toPeerID)
        }
    }

    fun sendFavoriteNotification(toPeerID: String, isFavorite: Boolean) {
        if (mesh.getPeerInfo(toPeerID)?.isConnected == true) {
            val myNpub = try { com.bluetalk.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(context)?.npub } catch (_: Exception) { null }
            val content = if (isFavorite) "[FAVORITED]:${myNpub ?: ""}" else "[UNFAVORITED]:${myNpub ?: ""}"
            val nickname = mesh.getPeerNicknames()[toPeerID] ?: toPeerID
            mesh.sendPrivateMessage(content, toPeerID, nickname)
        } else {
            nostr.sendFavoriteNotification(toPeerID, isFavorite)
        }
    }

    // Flush any queued messages for a specific peerID
    fun flushOutboxFor(peerID: String) {
        val queued = outbox[peerID] ?: return
        if (queued.isEmpty()) return
        Log.d(TAG, "Flushing outbox for ${peerID.take(8)}… count=${queued.size}")
        val iterator = queued.iterator()
        while (iterator.hasNext()) {
            val (content, nickname, messageID) = iterator.next()
            var hasMesh = mesh.getPeerInfo(peerID)?.isConnected == true && mesh.hasEstablishedSession(peerID)
            // If this is a noiseHex key, see if there is a connected mesh peer for this identity
            if (!hasMesh && peerID.length == 64 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                val meshPeer = resolveMeshPeerForNoiseHex(peerID)
                if (meshPeer != null && mesh.getPeerInfo(meshPeer)?.isConnected == true && mesh.hasEstablishedSession(meshPeer)) {
                    mesh.sendPrivateMessage(content, meshPeer, nickname, messageID)
                    iterator.remove()
                    continue
                }
            }
            val canNostr = canSendViaNostr(peerID)
            if (hasMesh) {
                mesh.sendPrivateMessage(content, peerID, nickname, messageID)
                iterator.remove()
            } else if (canNostr) {
                nostr.sendPrivateMessage(content, peerID, nickname, messageID)
                iterator.remove()
            }
        }
        if (queued.isEmpty()) {
            outbox.remove(peerID)
        }
    }

    // Flush everything (rarely used)
    fun flushAllOutbox() {
        outbox.keys.toList().forEach { flushOutboxFor(it) }
    }

    private fun canSendViaNostr(peerID: String): Boolean {
        return try {
            // Full Noise key hex
            if (peerID.length == 64 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                val noiseKey = hexToBytes(peerID)
                val fav = com.bluetalk.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey)
                fav?.isMutual == true && fav.peerNostrPublicKey != null
            } else if (peerID.length == 16 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                // Ephemeral 16-hex mesh ID: resolve via prefix match in favorites
                val fav = com.bluetalk.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(peerID)
                fav?.isMutual == true && fav.peerNostrPublicKey != null
            } else {
                false
            }
        } catch (_: Exception) { false }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 == 0) hex else "0$hex"
        return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun resolveMeshPeerForNoiseHex(noiseHex: String): String? {
        return try {
            mesh.getPeerNicknames().keys.firstOrNull { pid ->
                val info = mesh.getPeerInfo(pid)
                val keyHex = info?.noisePublicKey?.joinToString("") { b -> "%02x".format(b) }
                keyHex != null && keyHex.equals(noiseHex, ignoreCase = true)
            }
        } catch (_: Exception) { null }
    }

    // Called when mesh peer list changes; attempt to flush any matching outbox entries
    fun onPeersUpdated(peers: List<String>) {
        peers.forEach { pid ->
            flushOutboxFor(pid)
            val noiseHex = try {
                mesh.getPeerInfo(pid)?.noisePublicKey?.joinToString("") { b -> "%02x".format(b) }
            } catch (_: Exception) { null }
            noiseHex?.let { flushOutboxFor(it) }
        }
    }

    // Called when a Noise session becomes established; flush both the mesh peerID and its noiseHex alias
    fun onSessionEstablished(peerID: String) {
        flushOutboxFor(peerID)
        val noiseHex = try {
            mesh.getPeerInfo(peerID)?.noisePublicKey?.joinToString("") { b -> "%02x".format(b) }
        } catch (_: Exception) { null }
        noiseHex?.let { flushOutboxFor(it) }
    }
}
