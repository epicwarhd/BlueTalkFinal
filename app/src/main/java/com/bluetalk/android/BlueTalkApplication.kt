package com.bluetalk.android

import android.app.Application
import com.bluetalk.android.nostr.RelayDirectory
import com.bluetalk.android.ui.theme.ThemePreferenceManager
import com.bluetalk.android.net.ArtiTorManager

/**
 * BlueTalkApplication: Điểm khởi đầu của ứng dụng (Application class).
 * Lớp này chịu trách nhiệm khởi tạo các dịch vụ nền quan trọng ngay khi app vừa mở.
 */
class BlueTalkApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Khởi tạo mạng Tor (Arti) đầu tiên để đảm bảo mọi kết nối Internet sau đó đều được bảo mật/ẩn danh.
        try {
            val torProvider = ArtiTorManager.getInstance()
            torProvider.init(this)
        } catch (_: Exception){}

        // Tải danh sách các máy chủ Nostr (Relay) từ tệp assets/nostr_relays.csv.
        RelayDirectory.initialize(this)

        // Khởi tạo trình quản lý ghi chú theo vị trí (Location Notes) sớm để người dùng có thể xem ngay.
        try { com.bluetalk.android.nostr.LocationNotesInitializer.initialize(this) } catch (_: Exception) { }

        // Khởi tạo lưu trữ "Người dùng yêu thích" (Favorites) để có thể nhận dạng bạn bè ngay khi khởi động.
        try {
            com.bluetalk.android.favorites.FavoritesPersistenceService.initialize(this)
        } catch (_: Exception) { }

        // Khởi tạo định danh Nostr (Cặp khóa công khai/bí mật) của người dùng.
        try {
            com.bluetalk.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(this)
        } catch (_: Exception) { }

        // Khởi tạo tùy chỉnh giao diện (Sáng/Tối) từ bộ nhớ.
        ThemePreferenceManager.init(this)

        // Khởi tạo trình quản lý cài đặt gỡ lỗi (Debug settings).
        try { com.bluetalk.android.ui.debug.DebugPreferenceManager.init(this) } catch (_: Exception) { }

        // Khởi tạo các thanh đăng ký Geohash để theo dõi các phòng chat theo vị trí.
        try {
            com.bluetalk.android.nostr.GeohashAliasRegistry.initialize(this)
            com.bluetalk.android.nostr.GeohashConversationRegistry.initialize(this)
        } catch (_: Exception) { }

        // Khởi tạo tùy chỉnh cho dịch vụ Mesh Bluetooth.
        try { com.bluetalk.android.service.MeshServicePreferences.init(this) } catch (_: Exception) { }

        // Khởi động dịch vụ chạy ngầm (Foreground Service) để duy trì mạng Mesh kể cả khi người dùng đóng app.
        try { com.bluetalk.android.service.MeshForegroundService.start(this) } catch (_: Exception) { }

        // TorManager đã được khởi tạo ở trên.
    }
}

