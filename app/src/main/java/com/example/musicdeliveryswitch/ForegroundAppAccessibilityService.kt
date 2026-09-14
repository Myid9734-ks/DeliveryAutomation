package com.example.musicdeliveryswitch

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class ForegroundAppAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private var navigationTakeoverUntil = 0L
    private var deliveryExitGraceUntil = 0L
    private var pendingResumePackage: String? = null
    private var pendingResumeRunnable: Runnable? = null

    private data class PendingNaviScan(
        val scanPackage: String,   // 목적지 스캔할 네비 앱 패키지
        val expectedPkg: String,   // 리다이렉트 목표 네비 패키지
        val selectedNavi: String,
        val startedAt: Long
    )
    private var pendingNaviScan: PendingNaviScan? = null
    private var pendingNaviScanRunnable: Runnable? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // TYPE_WINDOW_STATE_CHANGED 만 처리 — 포그라운드 앱 전환 시 발생
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName == applicationContext.packageName) return

        // 비활성 윈도우(TMAP 플로팅 내비 위젯 등 오버레이)에서 온 이벤트 무시
        if (!isActiveWindow(event)) {
            NotificationLogWriter.appendDebugEvent(
                this, "foreground_event_skipped",
                "package" to packageName,
                "reason" to "inactive_window",
                "eventText" to event.text?.joinToString(" | ").orEmpty()
            )
            return
        }

        NotificationLogWriter.appendDebugEvent(
            this,
            "foreground_event",
            "package" to packageName,
            "eventType" to event.eventType,
            "eventText" to event.text?.joinToString(" | ").orEmpty()
        )

        val previousPackage = AppPrefs.lastForegroundPackage(this)

        handlePackageTransition(packageName, previousPackage, event)

        if (!AppPrefs.isMusicEnabled(this)) return

        val now = SystemClock.elapsedRealtime()

        handleNavigationForeground(packageName, previousPackage, now)

        if (handleDeliveryForeground(packageName, now)) return

        handleOtherForeground(packageName)
    }

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        cancelPendingResume()
        cancelPendingNaviScan()
        navigationTakeoverUntil = 0L
        deliveryExitGraceUntil = 0L
        AppPrefs.setLastForegroundPackage(this, "")
        NotificationLogWriter.appendDebugEvent(this, "accessibility_service_connected")
    }

    private fun handlePackageTransition(packageName: String, previousPackage: String, event: AccessibilityEvent) {
        if (previousPackage == packageName) return

        if (previousPackage in AppConstants.DELIVERY_PACKAGES && packageName in AppConstants.NAVIGATION_PACKAGES) {
            NotificationLogWriter.appendNavigationTransition(
                this,
                previousPackage,
                packageName,
                event.eventType,
                event.text?.joinToString(" | ").orEmpty()
            )
            NotificationLogWriter.appendDebugEvent(
                this,
                "foreground_transition",
                "fromPackage" to previousPackage,
                "toPackage" to packageName,
                "eventType" to event.eventType,
                "selectedNavi" to AppPrefs.selectedNavi(this)
            )
        }

        if (previousPackage in AppConstants.DELIVERY_PACKAGES && packageName !in AppConstants.DELIVERY_PACKAGES) {
            deliveryExitGraceUntil = SystemClock.elapsedRealtime() + AppConstants.DELIVERY_EXIT_GRACE_MS
            AppPrefs.setTargetActive(this, false)
            AppPrefs.setNavSessionActive(this, false)
            NotificationLogWriter.appendDebugEvent(
                this,
                "delivery_foreground_exit",
                "fromPackage" to previousPackage,
                "toPackage" to packageName,
                "graceMs" to AppConstants.DELIVERY_EXIT_GRACE_MS
            )
        }

        // 스캔 중인 네비 앱에서 다른 앱으로 전환되면 스캔 취소
        val scan = pendingNaviScan
        if (scan != null && packageName != scan.scanPackage) {
            cancelPendingNaviScan(reason = "foreground_changed_to:$packageName")
        }

        // grace 기간 중 배달앱의 빈 텍스트 이벤트(쿠팡 flicker)는 lastForeground 업데이트 생략
        // → runnable이 이전 내비 패키지를 기억한 채 재개 처리할 수 있음
        val isGraceFlicker = packageName in AppConstants.DELIVERY_PACKAGES &&
            SystemClock.elapsedRealtime() < deliveryExitGraceUntil &&
            event.text?.joinToString(" | ").orEmpty().isBlank()
        if (!isGraceFlicker) {
            AppPrefs.setLastForegroundPackage(this, packageName)
        }
    }

    private fun handleNavigationForeground(packageName: String, previousPackage: String, now: Long) {
        if (packageName !in AppConstants.NAVIGATION_PACKAGES) return

        // 배달앱에서 직접 열린 내비가 선택된 내비와 다르면 리다이렉트
        if (previousPackage in AppConstants.DELIVERY_PACKAGES) {
            redirectToSelectedNaviIfNeeded(packageName, previousPackage)
        }

        if (!AppPrefs.isTargetActive(this)) return

        navigationTakeoverUntil = now + AppConstants.NAVIGATION_TAKEOVER_MS
        AppPrefs.setTargetActive(this, false)
        AppPrefs.setNavSessionActive(this, true)
        NotificationLogWriter.appendDebugEvent(
            this,
            "navigation_foreground_entered",
            "package" to packageName,
            "takeoverUntilMs" to navigationTakeoverUntil
        )
    }

    private fun redirectToSelectedNaviIfNeeded(currentNaviPackage: String, fromPackage: String) {
        val selectedNavi = AppPrefs.selectedNavi(this)
        val expectedPkg = AppConstants.naviPackageOf(selectedNavi)
        if (currentNaviPackage == expectedPkg) return

        val destText = AppPrefs.lastDeliveryDestinationText(this)
        val destAt = AppPrefs.lastDeliveryDestinationAt(this)
        val cacheAgeMs = SystemClock.elapsedRealtime() - destAt

        NotificationLogWriter.appendDebugEvent(
            this,
            "navi_redirect_check",
            "currentNavi" to currentNaviPackage,
            "expectedPkg" to expectedPkg,
            "selectedNavi" to selectedNavi,
            "hasDestText" to destText.isNotBlank(),
            "cacheAgeMs" to cacheAgeMs
        )

        val skipReason = when {
            destText.isBlank() -> "no_dest_cache"
            cacheAgeMs > AppConstants.DELIVERY_DEST_CACHE_EXPIRY_MS -> "cache_expired"
            isUiNoise(destText) -> "dest_is_noise"
            else -> null
        }
        if (skipReason != null) {
            NotificationLogWriter.appendDebugEvent(
                this,
                "navi_redirect_skipped",
                "reason" to skipReason,
                "fromPackage" to fromPackage
            )
            // 쿠팡이 직접 실행한 네비 화면에서 목적지 텍스트 스캔 시도
            if (fromPackage == AppConstants.PKG_COUPANG_EATS) {
                schedulePendingNaviScan(currentNaviPackage, expectedPkg, selectedNavi)
            }
            return
        }

        NotificationLogWriter.appendDebugEvent(
            this,
            "navi_redirect_triggered",
            "from" to currentNaviPackage,
            "expectedPkg" to expectedPkg,
            "destText" to destText,
            "fromPackage" to fromPackage
        )

        try {
            val intent = Intent(this, NavigationRedirectActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("delivery://navi-redirect")
                putExtra(AppConstants.EXTRA_REDIRECT_FROM_PKG, currentNaviPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            NotificationLogWriter.appendDebugEvent(
                this,
                "navi_redirect_failed",
                "error" to "${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun handleDeliveryForeground(packageName: String, now: Long): Boolean {
        if (packageName !in AppConstants.DELIVERY_PACKAGES) return false

        if (now < deliveryExitGraceUntil) {
            // autoOpen으로 직접 전환 중인 앱이면 exit_grace 면제
            // (배민 사용 중 쿠팡 신규주문 → 배민 exit_grace 중 쿠팡 포그라운드 이벤트가 무시되는 문제 방지)
            if (AppPrefs.lastAutoOpenSentAt(this, packageName) > 0L) {
                NotificationLogWriter.appendDebugEvent(
                    this,
                    "exit_grace_bypassed",
                    "package" to packageName,
                    "reason" to "auto_open_in_progress"
                )
            } else {
                NotificationLogWriter.appendDebugEvent(
                    this,
                    "delivery_event_ignored",
                    "package" to packageName,
                    "reason" to "exit_grace",
                    "remainingMs" to (deliveryExitGraceUntil - now)
                )
                return true
            }
        }

        captureDeliveryDestination()
        cancelPendingNaviScan(reason = "delivery_foreground")
        cancelPendingResume()

        if (now < navigationTakeoverUntil) {
            NotificationLogWriter.appendDebugEvent(
                this,
                "delivery_event_ignored",
                "package" to packageName,
                "reason" to "navigation_takeover_window",
                "remainingMs" to (navigationTakeoverUntil - now)
            )
            return true
        }

        // contentIntent.send() 이후 실제 포그라운드 진입 여부 및 지연 시간 확인
        val autoOpenSentAt = AppPrefs.lastAutoOpenSentAt(this, packageName)
        if (autoOpenSentAt > 0L) {
            val elapsedMs = now - autoOpenSentAt
            NotificationLogWriter.appendDebugEvent(
                this,
                "delivery_app_foreground_confirmed",
                "package" to packageName,
                "elapsedMs" to elapsedMs,
                "slow" to (elapsedMs > 2000L)
            )
            AppPrefs.clearAutoOpenSentAt(this, packageName)
        }

        AppPrefs.setNavSessionActive(this, false)

        if (!AppPrefs.isTargetActive(this)) {
            AppPrefs.setTargetActive(this, true)
            if (MusicSessionHelper.isAnyMusicPlaying(this)) {
                MusicSessionHelper.pauseActivePlayer(this)
                NotificationLogWriter.appendDebugEvent(
                    this,
                    "music_pause_triggered",
                    "package" to packageName,
                    "reason" to "delivery_foreground"
                )
            } else if (AppPrefs.isAutoPaused(this) &&
                !MusicSessionHelper.isPaused(this, AppPrefs.activeMusicPackage(this).ifBlank { MusicSessionHelper.YOUTUBE_MUSIC })) {
                // 음악이 PAUSED 상태가 아닌데 autoPaused=true → stale 플래그 초기화
                // STATE_PAUSED는 우리가 일시정지한 정상 상태이므로 stale로 판단하지 않음
                AppPrefs.setAutoPaused(this, false)
                NotificationLogWriter.appendDebugEvent(
                    this,
                    "auto_paused_flag_cleared",
                    "package" to packageName,
                    "reason" to "stale_flag_on_delivery_foreground"
                )
            }
        }
        return true
    }

    private fun handleOtherForeground(packageName: String) {
        if (!AppPrefs.isAutoPaused(this)) return

        when (packageName) {
            in AppConstants.SYSTEM_DIALOG_PACKAGES -> {
                NotificationLogWriter.appendDebugEvent(
                    this, "resume_deferred", "package" to packageName, "reason" to "system_dialog"
                )
                scheduleResumeAfterDeliveryExit(packageName, AppConstants.RESUME_DELAY_SYSTEM_DIALOG_MS)
            }
            in AppConstants.OVERLAY_TRANSITION_PACKAGES -> {
                NotificationLogWriter.appendDebugEvent(
                    this, "resume_deferred", "package" to packageName, "reason" to "overlay_transition"
                )
                scheduleResumeAfterDeliveryExit(packageName, AppConstants.RESUME_DELAY_OVERLAY_MS)
            }
            else -> {
                NotificationLogWriter.appendDebugEvent(
                    this, "resume_scheduled", "package" to packageName
                )
                scheduleResumeAfterDeliveryExit(packageName, AppConstants.RESUME_DELAY_DEFAULT_MS)
            }
        }
    }

    private fun captureDeliveryDestination() {
        val root = rootInActiveWindow ?: return
        val candidates = mutableListOf<String>()
        collectNodeText(root, candidates, 0)
        val picked = pickDestinationCandidate(candidates) ?: return

        AppPrefs.setLastDeliveryDestinationText(this, picked)
        AppPrefs.setLastDeliveryDestinationAt(this, SystemClock.elapsedRealtime())
        NotificationLogWriter.appendNavigationIntent(
            this,
            Intent(Intent.ACTION_VIEW, Uri.parse("about:blank")),
            AppPrefs.selectedNavi(this),
            result = "captured_destination:$picked"
        )
        NotificationLogWriter.appendDebugEvent(
            this,
            "delivery_destination_captured",
            "picked" to picked,
            "candidateCount" to candidates.size
        )
    }

    private fun collectNodeText(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > AppConstants.DEST_TEXT_DEPTH_LIMIT) return

        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(out::add)
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(out::add)

        for (i in 0 until node.childCount) {
            try {
                collectNodeText(node.getChild(i), out, depth + 1)
            } catch (_: Exception) { }
        }
    }

    private fun pickDestinationCandidate(lines: List<String>): String? {
        val cleaned = lines
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length in AppConstants.DEST_TEXT_MIN_LEN..AppConstants.DEST_TEXT_MAX_LEN }
            .distinct()
            .filterNot { line -> isUiNoise(line) }

        cleaned.firstOrNull { line -> containsKeyword(line) }?.let { keywordLine ->
            val trimmed = keywordLine.substringAfter(':', keywordLine)
                .substringAfter(' ', keywordLine)
                .trim()
            return if (trimmed.isNotBlank()) trimmed else keywordLine
        }

        return cleaned.firstOrNull { line -> looksLikeAddress(line) } ?: cleaned.maxByOrNull { it.length }
    }

    private fun isUiNoise(line: String): Boolean {
        val englishNoise = listOf(
            "TMAP", "Kakao", "Naver", "route", "search", "direction",
            "delivery", "navigate", "cancel", "call", "close", "open"
        ).any { token -> line.contains(token, ignoreCase = true) }
        if (englishNoise) return true

        // 배달앱 공통 UI 문구 — 실제 주소가 아닌 안내/상태 텍스트
        val koreanNoise = listOf(
            "배달을 시작",   // 쿠팡이츠 대기화면
            "시작해보세요",
            "완료 시 최대",  // 배민 수익 안내
            "수락",
            "거절",
            "픽업",
            "배달비",
            "예상 소요",
            "평균",
            "건",
            "\uAD50\uD1B5\uBE44",   // 교통비
            "\uC218\uB839 \uAC74\uC218", // 수령 건수
            "\u27A1",               // ➔ 화살표 기호
            "➔",
            "→",
            // 쿠팡이츠 배달파트너 특유 노이즈
            "리워드 프로그램",
            "도착시간 임박",
            "보험에 가입",
            "순서변경불가",
            "운행시작",
            "탭 ",              // "탭 N개 중 N번째" 패턴
            "배달 파트너",
            "대기배달",
            // 지도/검색 버튼 텍스트 — "지도앱으로 검색하기" 등 UI 문구
            "검색하기",
            "지도앱"
        ).any { token -> line.contains(token) }
        return koreanNoise
    }

    private fun containsKeyword(line: String): Boolean {
        return listOf(
            "\uBAA9\uC801\uC9C0", "\uB3C4\uCC29\uC9C0", "\uBC30\uB2EC\uC9C0",
            "\uC8FC\uC18C", "\uC704\uCE58", "\uC7A5\uC18C"
        ).any { line.contains(it) }
    }

    private fun looksLikeAddress(line: String): Boolean {
        // 행정단위 글자가 단어 끝에 붙어야 주소 — "완료 시 최대"의 '시'처럼 접속사로 쓰인 경우 배제
        val pattern = Regex(
            "(?:[가-힣]+(?:시|도|군|구|읍|면|동|로|길|대로))" +
            "|\\d+[-\\s]?\\d*[동층호]"
        )
        return pattern.containsMatchIn(line)
    }

    private fun scheduleResumeAfterDeliveryExit(packageName: String, delayMs: Long) {
        cancelPendingResume()
        pendingResumePackage = packageName
        pendingResumeRunnable = Runnable {
            if (pendingResumePackage != packageName) return@Runnable
            val currentPackage = AppPrefs.lastForegroundPackage(this)
            if (currentPackage in AppConstants.DELIVERY_PACKAGES ||
                currentPackage in AppConstants.SYSTEM_DIALOG_PACKAGES ||
                currentPackage in AppConstants.OVERLAY_TRANSITION_PACKAGES) {
                handler.postDelayed(pendingResumeRunnable!!, delayMs)
                return@Runnable
            }
            pendingResumePackage = null
            pendingResumeRunnable = null
            MusicSessionHelper.resumeIfAutoPaused(this)
        }
        handler.postDelayed(pendingResumeRunnable!!, delayMs)
    }

    private fun schedulePendingNaviScan(scanPackage: String, expectedPkg: String, selectedNavi: String) {
        cancelPendingNaviScan()
        val scan = PendingNaviScan(scanPackage, expectedPkg, selectedNavi, SystemClock.elapsedRealtime())
        pendingNaviScan = scan
        pendingNaviScanRunnable = Runnable { executePendingNaviScan() }
        handler.postDelayed(pendingNaviScanRunnable!!, AppConstants.NAVI_DEST_SCAN_DELAY_MS)
        NotificationLogWriter.appendDebugEvent(
            this, "navi_dest_scan_scheduled",
            "scanPackage" to scanPackage,
            "expectedPkg" to expectedPkg,
            "selectedNavi" to selectedNavi
        )
    }

    private fun executePendingNaviScan() {
        val scan = pendingNaviScan ?: return

        val elapsedMs = SystemClock.elapsedRealtime() - scan.startedAt
        if (elapsedMs > AppConstants.NAVI_DEST_SCAN_TIMEOUT_MS) {
            NotificationLogWriter.appendDebugEvent(
                this, "navi_dest_scan_timeout",
                "scanPackage" to scan.scanPackage,
                "elapsedMs" to elapsedMs
            )
            pendingNaviScan = null
            pendingNaviScanRunnable = null
            return
        }

        val currentPackage = AppPrefs.lastForegroundPackage(this)
        if (currentPackage != scan.scanPackage) {
            NotificationLogWriter.appendDebugEvent(
                this, "navi_dest_scan_cancelled",
                "reason" to "foreground_changed",
                "currentPackage" to currentPackage
            )
            pendingNaviScan = null
            pendingNaviScanRunnable = null
            return
        }

        val root = rootInActiveWindow
        if (root == null) {
            pendingNaviScanRunnable = Runnable { executePendingNaviScan() }
            handler.postDelayed(pendingNaviScanRunnable!!, AppConstants.NAVI_DEST_SCAN_INTERVAL_MS)
            return
        }

        val candidates = mutableListOf<String>()
        collectNodeText(root, candidates, 0)
        val destText = pickNaviScreenDest(candidates)

        NotificationLogWriter.appendDebugEvent(
            this, "navi_dest_scan_attempt",
            "scanPackage" to scan.scanPackage,
            "elapsedMs" to elapsedMs,
            "candidateCount" to candidates.size,
            "found" to (destText != null),
            "destText" to (destText ?: "")
        )

        if (destText != null) {
            pendingNaviScan = null
            pendingNaviScanRunnable = null
            AppPrefs.setLastDeliveryDestinationText(this, destText)
            AppPrefs.setLastDeliveryDestinationAt(this, SystemClock.elapsedRealtime())
            try {
                val intent = Intent(this, NavigationRedirectActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = Uri.parse("delivery://navi-redirect")
                    putExtra(AppConstants.EXTRA_REDIRECT_FROM_PKG, scan.scanPackage)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(intent)
                NotificationLogWriter.appendDebugEvent(
                    this, "navi_redirect_after_scan",
                    "scanPackage" to scan.scanPackage,
                    "expectedPkg" to scan.expectedPkg,
                    "destText" to destText
                )
            } catch (e: Exception) {
                NotificationLogWriter.appendDebugEvent(
                    this, "navi_redirect_after_scan_failed",
                    "error" to "${e.javaClass.simpleName}: ${e.message}"
                )
            }
        } else {
            pendingNaviScanRunnable = Runnable { executePendingNaviScan() }
            handler.postDelayed(pendingNaviScanRunnable!!, AppConstants.NAVI_DEST_SCAN_INTERVAL_MS)
        }
    }

    // 네비 앱 화면(TMAP 등)에서 목적지 주소 텍스트 추출
    // 배달앱 목적지 캡처와 달리, 네비 앱 UI 노이즈를 별도로 처리
    private fun pickNaviScreenDest(candidates: List<String>): String? {
        val cleaned = candidates
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length in AppConstants.DEST_TEXT_MIN_LEN..AppConstants.DEST_TEXT_MAX_LEN }
            .distinct()
            .filterNot { isNaviScreenNoise(it) }

        // 명시적 목적지 키워드가 있는 라인 우선
        cleaned.firstOrNull { containsKeyword(it) }?.let { return it }
        // 주소 패턴이 있는 라인
        return cleaned.firstOrNull { looksLikeAddress(it) }
    }

    private fun isNaviScreenNoise(line: String): Boolean {
        // 네비 앱 공통 UI 문구 — 실제 주소/목적지가 아닌 상태/메뉴 텍스트
        val naviNoise = listOf(
            "TMAP", "카카오", "네이버", "경로", "km", "출발지", "최적", "일반", "고속", "무료",
            "안내 시작", "경로 탐색", "교통 정보", "현재 위치", "내 위치",
            "애플리케이션 아이콘", "설정", "검색", "즐겨찾기", "홈", "직장"
        ).any { token -> line.contains(token, ignoreCase = true) }
        if (naviNoise) return true

        // 숫자+단위 패턴 (거리/시간 표시) — 주소와 혼동 방지
        if (line.matches(Regex(".*\\d+\\s*[kmKM분시].*")) &&
            !looksLikeAddress(line)) return true

        return false
    }

    private fun cancelPendingNaviScan(reason: String = "") {
        val wasActive = pendingNaviScan != null
        pendingNaviScanRunnable?.let { handler.removeCallbacks(it) }
        pendingNaviScanRunnable = null
        pendingNaviScan = null
        if (wasActive && reason.isNotBlank()) {
            NotificationLogWriter.appendDebugEvent(
                this, "navi_dest_scan_cancelled", "reason" to reason
            )
        }
    }

    private fun cancelPendingResume() {
        pendingResumeRunnable?.let { handler.removeCallbacks(it) }
        pendingResumeRunnable = null
        pendingResumePackage = null
    }

// 이벤트가 실제 포그라운드(활성) 윈도우에서 온 것인지 확인
    // 내비 앱만 선별 필터링:
    //   - 창이 활성 상태면 항상 통과
    //   - 창이 비활성이고 직전 포그라운드가 배달앱이면 오버레이로 판단 → 무시
    //   - 창이 비활성이고 직전 포그라운드가 배달앱이 아니면 전환 중으로 판단 → 통과
    // 내비 외 앱은 필터링 없이 처리
    private fun isActiveWindow(event: AccessibilityEvent): Boolean {
        val packageName = event.packageName?.toString() ?: return true
        if (packageName !in AppConstants.NAVIGATION_PACKAGES) return true

        return try {
            val windowId = event.windowId
            if (windowId == -1) return true
            val window = windows?.find { it.id == windowId } ?: return true
            if (window.isActive) return true
            // 비활성 창 — 배달앱이 직전 포그라운드이거나 grace 기간 내이면 오버레이로 판단 → 무시
            // grace 기간 이후라면 전환 중으로 판단 → 허용
            val lastForeground = AppPrefs.lastForegroundPackage(this)
            if (lastForeground in AppConstants.DELIVERY_PACKAGES) return false
            if (SystemClock.elapsedRealtime() < deliveryExitGraceUntil) return false
            true
        } catch (_: Exception) {
            true
        }
    }

}