package com.example.musicdeliveryswitch

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ForegroundAppAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastDirectNavFallbackAt = 0L

    private val deliveryPackages = setOf(
        "com.woowahan.bros",
        "com.coupang.mobile.eats.courier"
    )

    private val navigationPackages = setOf(
        "com.skt.tmap.ku",
        "com.locnall.KimGiSa",
        "net.daum.android.map",
        "com.nhn.android.nmap"
    )

    private var navigationTakeoverUntil = 0L
    private var deliveryExitGraceUntil = 0L
    private var pendingResumePackage: String? = null
    private var pendingResumeRunnable: Runnable? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName == applicationContext.packageName) return

        val previousPackage = AppPrefs.lastForegroundPackage(this)

        if (previousPackage != packageName) {
            if (previousPackage in deliveryPackages && packageName in navigationPackages) {
                NotificationLogWriter.appendNavigationTransition(
                    this,
                    previousPackage,
                    packageName,
                    event.eventType,
                    event.text?.joinToString(" | ").orEmpty()
                )
                maybeFallbackToPreferredNavigation(packageName)
            }
            if (previousPackage in deliveryPackages && packageName !in deliveryPackages) {
                deliveryExitGraceUntil = SystemClock.elapsedRealtime() + 1200L
                AppPrefs.setTargetActive(this, false)
                AppPrefs.setNavSessionActive(this, false)
            }
            AppPrefs.setLastForegroundPackage(this, packageName)
        }

        if (!AppPrefs.isMusicEnabled(this)) return

        val now = SystemClock.elapsedRealtime()

        if (packageName in navigationPackages) {
            if (AppPrefs.isTargetActive(this)) {
                navigationTakeoverUntil = now + 1500L
                AppPrefs.setTargetActive(this, false)
                AppPrefs.setNavSessionActive(this, true)
            }
        }

        if (packageName in deliveryPackages) {
            if (now < deliveryExitGraceUntil) {
                return
            }
            captureDeliveryDestination()
            cancelPendingResume()

            if (now < navigationTakeoverUntil) {
                return
            }

            AppPrefs.setNavSessionActive(this, false)

            if (!AppPrefs.isTargetActive(this)) {
                AppPrefs.setTargetActive(this, true)
                if (MusicSessionHelper.isYoutubeMusicPlaying(this)) {
                    MusicSessionHelper.pauseYoutubeMusic(this)
                }
            }
            return
        }

        if (AppPrefs.isAutoPaused(this)) {
            scheduleResumeAfterDeliveryExit(packageName)
        }
    }

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        cancelPendingResume()
        navigationTakeoverUntil = 0L
        deliveryExitGraceUntil = 0L
        AppPrefs.setLastForegroundPackage(this, "")
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
    }

    private fun collectNodeText(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 12) return

        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(out::add)
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(out::add)

        for (i in 0 until node.childCount) {
            collectNodeText(node.getChild(i), out, depth + 1)
        }
    }

    private fun pickDestinationCandidate(lines: List<String>): String? {
        val cleaned = lines
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length in 4..80 }
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
        return listOf(
            "TMAP",
            "Kakao",
            "Naver",
            "route",
            "search",
            "direction",
            "delivery",
            "navigate",
            "cancel",
            "call",
            "close",
            "open"
        ).any { token -> line.contains(token, ignoreCase = true) }
    }

    private fun containsKeyword(line: String): Boolean {
        return listOf(
            "\uBAA9\uC801\uC9C0",
            "\uB3C4\uCC29\uC9C0",
            "\uBC30\uB2EC\uC9C0",
            "\uC8FC\uC18C",
            "\uC704\uCE58",
            "\uC7A5\uC18C"
        ).any { line.contains(it) }
    }

    private fun looksLikeAddress(line: String): Boolean {
        val pattern = Regex(".*(?:\uC2DC|\uB3C4|\uAD70|\uAD6C|\uC74D|\uBA74|\uB3D9|\uB85C|\uAE38).*")
        return pattern.containsMatchIn(line)
    }

    private fun maybeFallbackToPreferredNavigation(toPackage: String) {
        if (toPackage != "com.skt.tmap.ku" && toPackage != "com.locnall.KimGiSa") return

        val selected = AppPrefs.selectedNavi(this)
        if (selected == "TMAP") return

        val cached = AppPrefs.lastDeliveryDestinationText(this).trim()
        val cachedAt = AppPrefs.lastDeliveryDestinationAt(this)
        val now = SystemClock.elapsedRealtime()

        if (cached.isBlank()) return
        if (cachedAt <= 0L || now - cachedAt > 10 * 60 * 1000L) return
        if (now - lastDirectNavFallbackAt < 1500L) return

        lastDirectNavFallbackAt = now
        launchPreferredNavigation(selected, cached)
    }

    private fun launchPreferredNavigation(selected: String, query: String) {
        val encoded = Uri.encode(query)
        val target = when (selected) {
            "KAKAOMAP" -> Uri.parse("kakaomap://search?q=$encoded&viewType=MAP_CENTER&referrer=$packageName")
            "NAVER" -> Uri.parse("nmap://search?query=$encoded&appname=$packageName")
            else -> return
        }

        val targetPackage = when (selected) {
            "KAKAOMAP" -> "net.daum.android.map"
            "NAVER" -> "com.nhn.android.nmap"
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
        } catch (e: Exception) {
            NotificationLogWriter.appendNavigationIntent(
                this,
                null,
                selected,
                result = "direct_nav_fallback_fail:${e.javaClass.simpleName}:${e.message}"
            )
        }
    }

    private fun scheduleResumeAfterDeliveryExit(packageName: String) {
        cancelPendingResume()
        pendingResumePackage = packageName
        pendingResumeRunnable = Runnable {
            if (pendingResumePackage != packageName) return@Runnable
            val currentPackage = AppPrefs.lastForegroundPackage(this)
            if (currentPackage in deliveryPackages) {
                return@Runnable
            }

            pendingResumePackage = null
            pendingResumeRunnable = null
            MusicSessionHelper.resumeYoutubeMusicIfAutoPaused(this)
        }
        handler.postDelayed(pendingResumeRunnable!!, 1000L)
    }

    private fun cancelPendingResume() {
        pendingResumeRunnable?.let { handler.removeCallbacks(it) }
        pendingResumeRunnable = null
        pendingResumePackage = null
    }
}
