package com.example.musicdeliveryswitch

object AppConstants {
    // Music apps
    const val PKG_YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"
    const val PKG_SPOTIFY = "com.spotify.music"
    const val PKG_MELON = "com.music.melon"
    const val PKG_GENIE = "com.kt.genie"
    val SUPPORTED_MUSIC_APPS = listOf(
        PKG_YOUTUBE_MUSIC,
        PKG_SPOTIFY,
        PKG_MELON,
        PKG_GENIE
    )

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

    // 카카오내비 딥링크용 앱키 (카카오 개발자 콘솔 > 내 애플리케이션 > 앱 키 > REST API 키)
    const val KAKAONAVI_APP_KEY = ""

    fun naviPackageOf(navi: String): String = when (navi) {
        NAVI_KAKAONAVI -> PKG_KAKAONAVI
        NAVI_KAKAOMAP -> PKG_KAKAOMAP
        NAVI_NAVER -> PKG_NAVERMAP
        else -> PKG_TMAP
    }

    // Timing (ms)
    const val DELIVERY_EXIT_GRACE_MS = 1200L
    const val NAVIGATION_TAKEOVER_MS = 1500L
    const val RESUME_DELAY_SYSTEM_DIALOG_MS = 1500L
    const val RESUME_DELAY_OVERLAY_MS = 2500L
    const val RESUME_DELAY_DEFAULT_MS = 1000L
    const val NAV_FALLBACK_RATE_LIMIT_MS = 1500L
    const val DELIVERY_DEST_CACHE_EXPIRY_MS = 10 * 60 * 1000L
    const val DELIVERY_AUTO_OPEN_DEDUPE_MS = 2000L
    const val LAUNCH_INTENT_DELAY_MS = 600L    // 알림음이 시작된 후 앱 전환 (즉시 전환 시 Android가 알림음 억제)
    const val CONTENT_INTENT_DELAY_MS = 1500L  // launchIntent 발동 후 주문화면 딥링크 (포그라운드 확인 대기)

    // 쿠팡 → 잘못된 네비 실행 시, 해당 네비 화면에서 목적지 텍스트 스캔
    const val NAVI_DEST_SCAN_DELAY_MS = 1500L      // 첫 스캔 전 대기 (네비 UI 로딩 대기)
    const val NAVI_DEST_SCAN_INTERVAL_MS = 1000L   // 재시도 간격
    const val NAVI_DEST_SCAN_TIMEOUT_MS = 10_000L  // 최대 스캔 시간

    // Intent extra: 리다이렉트 전 실행 중이던 네비 패키지 (종료 대상)
    const val EXTRA_REDIRECT_FROM_PKG = "redirect_from_pkg"

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
