package com.bluetalk.android.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bluetalk.android.noise.NoiseEncryptionService
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import androidx.core.content.edit

/**
 * EncryptionService: Dịch vụ mã hóa cốt lõi của ứng dụng.
 * Lớp này quản lý các hoạt động bảo mật bao gồm:
 * 1. Tạo và lưu trữ cặp khóa định danh (Ed25519) để ký tên tin nhắn.
 * 2. Triển khai giao thức Noise để mã hóa đầu cuối (E2EE) cho các cuộc trò chuyện riêng tư.
 * 3. Quản lý lưu trữ an toàn các thông tin nhạy cảm.
 */
open class EncryptionService(private val context: Context) {
    
    companion object {
        private const val TAG = "EncryptionService"
        private const val ED25519_PRIVATE_KEY_PREF = "ed25519_signing_private_key"
        private const val OLD_PREFS_NAME = "bluetalk_crypto"
        private const val SECURE_PREFS_NAME = "bluetalk_crypto_secure"
    }
    
    // NoiseEncryptionService: Xử lý các chi tiết kỹ thuật của giao thức Noise (mã hóa kênh truyền).
    private val noiseService: NoiseEncryptionService by lazy { NoiseEncryptionService(context) }
    
    // Theo dõi các phiên kết nối đã được thiết lập thành công.
    private val establishedSessions = ConcurrentHashMap<String, String>() // peerID -> fingerprint
    
    // Cặp khóa Ed25519 dùng để tạo chữ ký số (Digital Signature).
    private lateinit var ed25519PrivateKey: Ed25519PrivateKeyParameters
    private lateinit var ed25519PublicKey: Ed25519PublicKeyParameters
    
    /**
     * Hàm mã hóa dữ liệu dành cho một người nhận cụ thể (Sử dụng Noise protocol).
     */
    @Throws(Exception::class)
    fun encrypt(data: ByteArray, peerID: String): ByteArray {
        val encrypted = noiseService.encrypt(data, peerID)
        if (encrypted == null) {
            throw Exception("Failed to encrypt for $peerID")
        }
        return encrypted
    }
    
    /**
     * Hàm giải mã dữ liệu từ một người gửi cụ thể.
     */
    @Throws(Exception::class)
    fun decrypt(data: ByteArray, peerID: String): ByteArray {
        val decrypted = noiseService.decrypt(data, peerID)
        if (decrypted == null) {
            throw Exception("Failed to decrypt from $peerID")
        }
        return decrypted
    }
    
    /**
     * Hàm ký tên vào dữ liệu bằng khóa bí mật Ed25519.
     */
    fun signData(data: ByteArray): ByteArray? {
        return try {
            val signer = Ed25519Signer()
            signer.init(true, ed25519PrivateKey)
            signer.update(data, 0, data.size)
            val signature = signer.generateSignature()
            Log.d(TAG, "✅ Generated Ed25519 signature (${signature.size} bytes)")
            signature
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to sign data with Ed25519: ${e.message}")
            null
        }
    }
    
    // Các hàm phản hồi cho giao diện người dùng.
    var onSessionEstablished: ((String) -> Unit)? = null // peerID
    var onSessionLost: ((String) -> Unit)? = null // peerID
    var onHandshakeRequired: ((String) -> Unit)? = null // peerID
    private lateinit var prefs: SharedPreferences
    
    init {
        initialize()
    }

    private fun setUpEncryptedPrefs() {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        // Tạo bộ nhớ tùy chọn được mã hóa (EncryptedSharedPreferences) để lưu trữ khóa bí mật.
        prefs = EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Logic khởi tạo hệ thống mã hóa.
     */
    protected open fun initialize() {
        setUpEncryptedPrefs()
        // Khởi tạo hoặc tải cặp khóa ký tên Ed25519 từ bộ nhớ.
        val keyPair = loadOrCreateEd25519KeyPair()
        ed25519PrivateKey = keyPair.private as Ed25519PrivateKeyParameters
        ed25519PublicKey = keyPair.public as Ed25519PublicKeyParameters
        
        Log.d(TAG, "✅ Ed25519 signing keys initialized")
        
        // Thiết lập các hàm gọi ngược (callback) cho NoiseEncryptionService.
        noiseService.onPeerAuthenticated = { peerID, fingerprint ->
            Log.d(TAG, "✅ Noise session established with $peerID, fingerprint: ${fingerprint.take(16)}...")
            establishedSessions[peerID] = fingerprint
            onSessionEstablished?.invoke(peerID)
        }
        
        noiseService.onHandshakeRequired = { peerID ->
            Log.d(TAG, "🤝 Handshake required for $peerID")
            onHandshakeRequired?.invoke(peerID)
        }
    }
    
    /**
     * Thêm khóa công khai của người dùng khác và bắt đầu bắt tay bảo mật (Handshake).
     */
    @Throws(Exception::class)
    fun addPeerPublicKey(peerID: String, publicKeyData: ByteArray) {
        Log.d(TAG, "Legacy addPeerPublicKey called for $peerID with ${publicKeyData.size} bytes")
        
        if (!hasEstablishedSession(peerID)) {
            Log.d(TAG, "No Noise session with $peerID, initiating handshake")
            initiateHandshake(peerID)
        }
    }
    
    /**
     * Get peer's identity key (fingerprint) for favorites
     */
    fun getPeerIdentityKey(peerID: String): ByteArray? {
        val fingerprint = getPeerFingerprint(peerID) ?: return null
        return fingerprint.toByteArray()
    }
    
    /**
     * Clear persistent identity (for panic mode)
     */
    fun clearPersistentIdentity() {
        noiseService.clearPersistentIdentity()
        establishedSessions.clear()
        
        // Clear Ed25519 signing key from preferences
        try {
            prefs.edit { remove(ED25519_PRIVATE_KEY_PREF) }
            Log.d(TAG, "🗑️ Cleared Ed25519 signing keys from preferences")

            // Generate new keys immediately
            val keyPair = loadOrCreateEd25519KeyPair()
            ed25519PrivateKey = keyPair.private as Ed25519PrivateKeyParameters
            ed25519PublicKey = keyPair.public as Ed25519PublicKeyParameters
            Log.d(TAG, "✅ Rotated Ed25519 signing keys in memory")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to clear Ed25519 keys: ${e.message}")
        }
    }
    
    /**
     * Encrypt data for a specific peer using Noise transport encryption
     */
    @Throws(Exception::class)
    fun encrypt(data: ByteArray, peerID: String): ByteArray {
        val encrypted = noiseService.encrypt(data, peerID)
        if (encrypted == null) {
            throw Exception("Failed to encrypt for $peerID")
        }
        return encrypted
    }
    
    /**
     * Decrypt data from a specific peer using Noise transport encryption
     */
    @Throws(Exception::class)
    fun decrypt(data: ByteArray, peerID: String): ByteArray {
        val decrypted = noiseService.decrypt(data, peerID)
        if (decrypted == null) {
            throw Exception("Failed to decrypt from $peerID")
        }
        return decrypted
    }
    
    /**
     * Sign data using our static identity key
     * Note: This is now done at the packet level, not per-message
     */
    @Throws(Exception::class)
    fun sign(data: ByteArray): ByteArray {
        // Note: In Noise protocol, authentication is built into the handshake
        // For compatibility, we return empty signature
        return ByteArray(0)
    }
    
    /**
     * Verify signature using peer's identity key
     * Note: This is now done at the packet level, not per-message
     */
    @Throws(Exception::class)
    fun verify(signature: ByteArray, data: ByteArray, peerID: String): Boolean {
        // Note: In Noise protocol, authentication is built into the transport
        // Messages are authenticated automatically when decrypted
        return hasEstablishedSession(peerID)
    }
    
    // MARK: - Noise Protocol Interface
    
    /**
     * Check if we have an established Noise session with a peer
     */
    fun hasEstablishedSession(peerID: String): Boolean {
        return noiseService.hasEstablishedSession(peerID)
    }
    
    /**
     * Get session state for a peer (for UI state display)
     */
    fun getSessionState(peerID: String): com.bluetalk.android.noise.NoiseSession.NoiseSessionState {
        return noiseService.getSessionState(peerID)
    }
    
    /**
     * Get encryption icon state for UI
     */
    fun shouldShowEncryptionIcon(peerID: String): Boolean {
        return hasEstablishedSession(peerID)
    }
    
    /**
     * Get peer fingerprint for favorites/blocking
     */
    fun getPeerFingerprint(peerID: String): String? {
        return noiseService.getPeerFingerprint(peerID)
    }
    
    /**
     * Get current peer ID for a fingerprint (for peer ID rotation)
     */
    fun getCurrentPeerID(fingerprint: String): String? {
        return noiseService.getPeerID(fingerprint)
    }
    
    /**
     * Initiate a Noise handshake with a peer
     */
    fun initiateHandshake(peerID: String): ByteArray? {
        Log.d(TAG, "🤝 Initiating Noise handshake with $peerID")
        return noiseService.initiateHandshake(peerID)
    }
    
    /**
     * Process an incoming handshake message
     */
    fun processHandshakeMessage(data: ByteArray, peerID: String): ByteArray? {
        Log.d(TAG, "🤝 Processing handshake message from $peerID")
        return noiseService.processHandshakeMessage(data, peerID)
    }
    
    /**
     * Remove a peer session (called when peer disconnects)
     */
    fun removePeer(peerID: String) {
        establishedSessions.remove(peerID)
        noiseService.removePeer(peerID)
        onSessionLost?.invoke(peerID)
        Log.d(TAG, "🗑️ Removed session for $peerID")
    }
    
    /**
     * Update peer ID mapping (for peer ID rotation)
     */
    fun updatePeerIDMapping(oldPeerID: String?, newPeerID: String, fingerprint: String) {
        oldPeerID?.let { establishedSessions.remove(it) }
        establishedSessions[newPeerID] = fingerprint
        noiseService.updatePeerIDMapping(oldPeerID, newPeerID, fingerprint)
    }
    
    // MARK: - Channel Encryption
    
    /**
     * Set password for a channel (derives encryption key using Argon2id)
     */
    fun setChannelPassword(password: String, channel: String) {
        noiseService.setChannelPassword(password, channel)
    }
    
    /**
     * Encrypt message for a password-protected channel
     */
    fun encryptChannelMessage(message: String, channel: String): ByteArray? {
        return noiseService.encryptChannelMessage(message, channel)
    }
    
    /**
     * Decrypt channel message
     */
    fun decryptChannelMessage(encryptedData: ByteArray, channel: String): String? {
        return noiseService.decryptChannelMessage(encryptedData, channel)
    }
    
    /**
     * Remove channel password (when leaving channel)
     */
    fun removeChannelPassword(channel: String) {
        noiseService.removeChannelPassword(channel)
    }
    
    // MARK: - Session Management
    
    /**
     * Get all peers with established sessions
     */
    fun getEstablishedPeers(): List<String> {
        return establishedSessions.keys.toList()
    }
    
    /**
     * Get sessions that need rekeying
     */
    fun getSessionsNeedingRekey(): List<String> {
        return noiseService.getSessionsNeedingRekey()
    }
    
    /**
     * Initiate rekey for a session
     */
    fun initiateRekey(peerID: String): ByteArray? {
        Log.d(TAG, "🔄 Initiating rekey for $peerID")
        establishedSessions.remove(peerID) // Will be re-added when new session is established
        return noiseService.initiateRekey(peerID)
    }
    
    /**
     * Get our identity fingerprint
     */
    fun getIdentityFingerprint(): String {
        return noiseService.getIdentityFingerprint()
    }
    
    /**
     * Get debug information about encryption state
     */
    fun getDebugInfo(): String = buildString {
        appendLine("=== EncryptionService Debug ===")
        appendLine("Established Sessions: ${establishedSessions.size}")
        appendLine("Our Fingerprint: ${getIdentityFingerprint().take(16)}...")
        
        if (establishedSessions.isNotEmpty()) {
            appendLine("Active Encrypted Sessions:")
            establishedSessions.forEach { (peerID, fingerprint) ->
                appendLine("  $peerID -> ${fingerprint.take(16)}...")
            }
        }
        
        appendLine("")
        appendLine(noiseService.toString()) // Include NoiseService state
    }
    
    /**
     * Shutdown encryption service
     */
    fun shutdown() {
        establishedSessions.clear()
        noiseService.shutdown()
        Log.d(TAG, "🔌 EncryptionService shut down")
    }
    
    // MARK: - Ed25519 Signature Verification
    
    /**
     * Verify Ed25519 signature against data using a public key
     */
    open fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKeyBytes: ByteArray): Boolean {
        return try {
            val publicKey = Ed25519PublicKeyParameters(publicKeyBytes, 0)
            val verifier = Ed25519Signer()
            verifier.init(false, publicKey)
            verifier.update(data, 0, data.size)
            val isValid = verifier.verifySignature(signature)
            Log.d(TAG, "✅ Ed25519 signature verification: $isValid")
            isValid
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to verify Ed25519 signature: ${e.message}")
            false
        }
    }
    
    // MARK: - Private Key Management
    
    /**
     * Load existing Ed25519 key pair from preferences or create a new one
     */
    private fun loadOrCreateEd25519KeyPair(): AsymmetricCipherKeyPair {
        // Migrate legacy plaintext Ed25519 key to encrypted storage if present
        migrateOldEd25519KeyIfNeeded()
        try {
            val storedKey = prefs.getString(ED25519_PRIVATE_KEY_PREF, null)

            if (storedKey != null) {
                // Load existing key
                val privateKeyBytes = Base64.decode(storedKey, Base64.DEFAULT)
                val privateKey = Ed25519PrivateKeyParameters(privateKeyBytes, 0)
                val publicKey = privateKey.generatePublicKey()
                Log.d(TAG, "✅ Loaded existing Ed25519 signing key pair")
                return AsymmetricCipherKeyPair(publicKey, privateKey)
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to load existing Ed25519 key, creating new one: ${e.message}")
        }
        
        // Create new key pair
        return generateAndSaveEd25519KeyPair()
    }

    fun generateAndSaveEd25519KeyPair(): AsymmetricCipherKeyPair {
        val keyGen = Ed25519KeyPairGenerator()
        keyGen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val keyPair = keyGen.generateKeyPair()

        // Store private key in preferences
        try {
            val privateKey = keyPair.private as Ed25519PrivateKeyParameters
            val privateKeyBytes = privateKey.encoded
            val encodedKey = Base64.encodeToString(privateKeyBytes, Base64.DEFAULT)

            prefs.edit { putString(ED25519_PRIVATE_KEY_PREF, encodedKey) }
            Log.d(TAG, "✅ Created and stored new Ed25519 signing key pair")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to store Ed25519 private key: ${e.message}")
        }
        
        return keyPair
    }

    private fun migrateOldEd25519KeyIfNeeded() {
        try {
            // old existing plain text preference
            val oldPrefs = context.getSharedPreferences(OLD_PREFS_NAME, Context.MODE_PRIVATE)

            val oldKey = oldPrefs.getString(ED25519_PRIVATE_KEY_PREF, null)

            if (oldKey != null && !prefs.contains(ED25519_PRIVATE_KEY_PREF)) {
                prefs.edit {
                    putString(ED25519_PRIVATE_KEY_PREF, oldKey)
                }
                oldPrefs.edit {
                    remove(ED25519_PRIVATE_KEY_PREF)
                }
                Log.d(TAG, "🔁 Migrated Ed25519 key to EncryptedSharedPreferences")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to migrate Ed25519 key; generating new identity: ${e.message}")
        }
    }
}
