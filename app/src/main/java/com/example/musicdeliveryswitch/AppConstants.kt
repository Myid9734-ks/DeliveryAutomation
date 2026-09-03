package com.example.musicdeliveryswitch

object AppConstants {
    // Delivery apps
    const val PKG_BAEMIN = "com.woowahan.bros"
    const val PKG_COUPANG_EATS = "com.coupang.mobile.eats.courier"
    val DELIVERY_PACKAGES = setOf(PKG_BAEMIN, PKG_COUPANG_EATS)

    fun deliveryAppName(packageName: String): String = when (packageName) {
        PKG_BAEMIN -> "배민"
        PKG_COUPANG_EATS -> "쿠팡"
        else -> packageName
    }

    // Notification matching
    const val BAEMIN_CHANNEL_NEW_ORDER = "BROS_DELIVERY_ALLOCATION_NOTI"
    const val BAEMIN_TITLE_NEW_ORDER = "신규배달"
    const val BAEMIN_TEXT_NEW_ORDER = "새로운 배달이 배정되었습니다."
    const val COUPANG_CHANNEL_NEW_ORDER = "COURIER_ASSIGNMENT"
    const val COUPANG_TEXT_NEW_ORDER = "주문"

    // Navigation apps
    const val PKG_TMAP = "com.skt.tmap.ku"
    const val PKG_KAKAONAVI = "com.locnall.KimGiSa"
    const val PKG_KAKAOMAP = "net.daum.android.map"
    const val PKG_NAVERMAP = "com.nhn.android.nmap"
    val NAVIGATION_PACKAGES = setOf(PKG_TMAP, PKG_KAKAONAVI, PKG_KAKAOMAP, PKG_NAVERMAP)

    // Navigation app identifiers (stored in prefs)
    const val NAVI_TMAP = "TMAP"
    const val NAVI_KAKAONAVI = "KAKAONAVI"
    const val NAVI_KAKAOMAP = "KAKAOMAP"
    const val NAVI_NAVER = "NAVER"

    // Timing (ms)
    const val DELIVERY_EXIT_GRACE_MS = 1200L
    const val NAVIGATION_TAKEOVER_MS = 1500L
    const val RESUME_DELAY_SYSTEM_DIALOG_MS = 1500L
    const val RESUME_DELAY_OVERLAY_MS = 2500L
    const val RESUME_DELAY_DEFAULT_MS = 1000L
    const val NAV_FALLBACK_RATE_LIMIT_MS = 1500L
    const val DELIVERY_DEST_CACHE_EXPIRY_MS = 10 * 60 * 1000L
    const val DELIVERY_AUTO_OPEN_DEDUPE_MS = 2000L

    // System packages (resume scheduling special cases)
    val SYSTEM_DIALOG_PACKAGES = setOf(
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.android.permissioncontroller"
    )
    val OVERLAY_TRANSITION_PACKAGES = setOf(
        "com.sec.android.app.launcher",
        "com.android.systemui"
    )

    // UI text capture
    const val DEST_TEXT_DEPTH_LIMIT = 12
    const val DEST_TEXT_MIN_LEN = 4
    const val DEST_TEXT_MAX_LEN = 80
}
