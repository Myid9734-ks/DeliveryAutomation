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
    private var lastDirectNavFallbackAt = 0L

    private var navigationTakeoverUntil = 0L
    private var deliveryExitGraceUntil = 0L
    private var pendingResumePackage: String? = null
    private var pendingResumeRunnable: Runnable? = null

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

        handleNavigationForeground(packageName, now)

        if (handleDeliveryForeground(packageName, now)) return

        handleOtherForeground(packageName)
    }

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        cancelPendingResume()
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
            maybeFallbackToPreferredNavigation(packageName)
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

        // grace 기간 중 배달앱의 빈 텍스트 이벤트(쿠팡 flicker)는 lastForeground 업데이트 생략
        // → runnable이 이전 내비 패키지를 기억한 채 재개 처리할 수 있음
        val isGraceFlicker = packageName in AppConstants.DELIVERY_PACKAGES &&
            SystemClock.elapsedRealtime() < deliveryExitGraceUntil &&
            event.text?.joinToString(" | ").orEmpty().isBlank()
        if (!isGraceFlicker) {
            AppPrefs.setLastForegroundPackage(this, packageName)
        }
    }

    private fun handleNavigationForeground(packageName: String, now: Long) {
        if (packageName !in AppConstants.NAVIGATION_PACKAGES) return
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

    private fun handleDeliveryForeground(packageName: String, now: Long): Boolean {
        if (packageName !in AppConstants.DELIVERY_PACKAGES) return false

        if (now < deliveryExitGraceUntil) {
            NotificationLogWriter.appendDebugEvent(
                this,
                "delivery_event_ignored",
                "package" to packageName,
                "reason" to "exit_grace",
                "remainingMs" to (deliveryExitGraceUntil - now)
            )
            return true
        }

        captureDeliveryDestination()
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

        AppPrefs.setNavSessionActive(this, false)

        if (!AppPrefs.isTargetActive(this)) {
            AppPrefs.setTargetActive(this, true)
            if (MusicSessionHelper.isYoutubeMusicPlaying(this)) {
                MusicSessionHelper.pauseYoutubeMusic(this)
                NotificationLogWriter.appendDebugEvent(
                    this,
                    "music_pause_triggered",
                    "package" to packageName,
                    "reason" to "delivery_foreground"
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
            "→"
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

    private fun maybeFallbackToPreferredNavigation(toPackage: String) {
        if (toPackage != AppConstants.PKG_TMAP && toPackage != AppConstants.PKG_KAKAONAVI) return

        val selected = AppPrefs.selectedNavi(this)
        if (selected == AppConstants.NAVI_TMAP) return

        val cached = AppPrefs.lastDeliveryDestinationText(this).trim()
        val cachedAt = AppPrefs.lastDeliveryDestinationAt(this)
        val now = SystemClock.elapsedRealtime()

        if (cached.isBlank()) {
            NotificationLogWriter.appendDebugEvent(
                this, "direct_nav_fallback_skipped", "reason" to "empty_cache", "selectedNavi" to selected
            )
            return
        }
        if (cachedAt <= 0L || now - cachedAt > AppConstants.DELIVERY_DEST_CACHE_EXPIRY_MS) {
            NotificationLogWriter.appendDebugEvent(
                this, "direct_nav_fallback_skipped", "reason" to "cache_expired", "selectedNavi" to selected
            )
            return
        }
        if (now - lastDirectNavFallbackAt < AppConstants.NAV_FALLBACK_RATE_LIMIT_MS) {
            NotificationLogWriter.appendDebugEvent(
                this, "direct_nav_fallback_skipped", "reason" to "rate_limited", "selectedNavi" to selected
            )
            return
        }

        lastDirectNavFallbackAt = now
        NotificationLogWriter.appendDebugEvent(
            this,
            "direct_nav_fallback_attempt",
            "selectedNavi" to selected,
            "cachedDestination" to cached,
            "toPackage" to toPackage
        )
        launchPreferredNavigation(selected, cached)
    }

    private fun launchPreferredNavigation(selected: String, query: String) {
        val encoded = Uri.encode(query)
        val target = when (selected) {
            AppConstants.NAVI_KAKAOMAP -> Uri.parse("kakaomap://search?q=$encoded&viewType=MAP_CENTER&referrer=$packageName")
            AppConstants.NAVI_NAVER -> Uri.parse("nmap://search?query=$encoded&appname=$packageName")
            else -> return
        }
        val targetPackage = when (selected) {
            AppConstants.NAVI_KAKAOMAP -> AppConstants.PKG_KAKAOMAP
            AppConstants.NAVI_NAVER -> AppConstants.PKG_NAVERMAP
            else -> return
        }

        try {
            startActivity(Intent(Intent.ACTION_VIEW, target).apply {
                setPackage(targetPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            NotificationLogWriter.appendNavigationIntent(
                this,
                Intent(Intent.ACTION_VIEW, target).apply { setPackage(targetPackage) },
                selected,
                result = "direct_nav_fallback_ok"
            )
            NotificationLogWriter.appendDebugEvent(
                this, "direct_nav_fallback_result", "result" to "ok",
                "selectedNavi" to selected, "targetPackage" to targetPackage
            )
        } catch (e: Exception) {
            NotificationLogWriter.appendNavigationIntent(
                this, null, selected,
                result = "direct_nav_fallback_fail:${e.javaClass.simpleName}:${e.message}"
            )
            NotificationLogWriter.appendDebugEvent(
                this, "direct_nav_fallback_result", "result" to "fail",
                "selectedNavi" to selected, "targetPackage" to targetPackage,
                "error" to "${e.javaClass.simpleName}: ${e.message}"
            )
        }
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
            MusicSessionHelper.resumeYoutubeMusicIfAutoPaused(this)
        }
        handler.postDelayed(pendingResumeRunnable!!, delayMs)
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