package com.example.musicdeliveryswitch

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MusicNotificationListener : NotificationListenerService() {

    // packageName → MediaController (SUPPORTED_MUSIC_APPS 전체 관리)
    private val controllers = mutableMapOf<String, MediaController>()
    private val lastAutoOpenAt = mutableMapOf<String, Long>()
    // 패키지 단위 중복 방지 — 같은 앱에서 다른 키로 연속 알림 시(배민 FCM 중복 발송 등) 차단
    private val lastAutoOpenPkgAt = mutableMapOf<String, Long>()

    // 컨트롤러 부착 재시도 — 음악 앱 세션이 늦게 열리는 경우를 대비
    private val attachRetryHandler = Handler(Looper.getMainLooper())
    private var attachRetryScheduled = false
    private var attachRetryCount = 0

    companion object {
        private const val ATTACH_RETRY_MAX = 6
        private const val ATTACH_RETRY_DELAY_MS = 350L
    }

    /** 앱별 콜백 — 재생 재개 완료 감지 (autoPaused 플래그 초기화) */
    private fun makeCallback(packageName: String) = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            NotificationLogWriter.appendAutoOpenResult(
                this@MusicNotificationListener,
                packageName,
                "playback_state_changed",
                "state=${state?.state ?: -1}, autoPaused=${AppPrefs.isAutoPaused(this@MusicNotificationListener)}, targetActive=${AppPrefs.isTargetActive(this@MusicNotificationListener)}, resumePending=${AppPrefs.isResumePending(this@MusicNotificationListener)}"
            )
            if (state?.state != PlaybackState.STATE_PLAYING) return
            if (!AppPrefs.isAutoPaused(this@MusicNotificationListener)) return
            // 멈춘 앱과 재생 시작한 앱이 다르면 무시
            if (AppPrefs.activeMusicPackage(this@MusicNotificationListener) != packageName) return
            // 배달앱이 포그라운드 상태면 방금 pause 요청이 간 것 — resume complete 처리하지 않음
            // (resume + immediate pause 레이스로 autoPaused가 잘못 초기화되는 버그 방지)
            if (AppPrefs.isTargetActive(this@MusicNotificationListener)) return

            val resumeAt = AppPrefs.resumeRequestedAt(this@MusicNotificationListener)
            val elapsed = SystemClock.elapsedRealtime() - if (resumeAt > 0L) resumeAt else AppPrefs.autoPauseAt(this@MusicNotificationListener)
            NotificationLogWriter.appendAutoOpenResult(
                this@MusicNotificationListener,
                packageName,
                "auto_resume_complete",
                "playing_after=${elapsed}ms"
            )
            AppPrefs.setAutoPaused(this@MusicNotificationListener, false)
            AppPrefs.setAutoPauseAt(this@MusicNotificationListener, 0L)
            AppPrefs.setResumePending(this@MusicNotificationListener, false)
            AppPrefs.setResumeRequestedAt(this@MusicNotificationListener, 0L)
            AppPrefs.clearActiveMusicPackage(this@MusicNotificationListener)
        }
    }

    // packageName → 등록된 Callback 인스턴스 (unregister 시 동일 객체 필요)
    private val registeredCallbacks = mutableMapOf<String, MediaController.Callback>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        attachMusicControllers()
    }

    override fun onListenerDisconnected() {
        detachAllControllers()
        attachRetryHandler.removeCallbacksAndMessages(null)
        attachRetryScheduled = false
        attachRetryCount = 0
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        if (sbn.packageName in AppConstants.DELIVERY_PACKAGES) {
            NotificationLogWriter.append(this, sbn)
            NotificationLogWriter.appendDebugEvent(
                this,
                "delivery_notification_posted",
                "package" to sbn.packageName,
                "key" to sbn.key,
                "channelId" to if (Build.VERSION.SDK_INT >= 26) sbn.notification.channelId else null,
                "autoOpenEnabled" to AppPrefs.isOrderAutoOpenEnabled(this)
            )

            if (AppPrefs.isOrderAutoOpenEnabled(this) && isNewOrderNotification(sbn)) {
                NotificationLogWriter.appendDebugEvent(
                    this,
                    "new_order_detected",
                    "package" to sbn.packageName,
                    "channelId" to if (Build.VERSION.SDK_INT >= 26) sbn.notification.channelId else null,
                    "action" to "open_delivery_app"
                )
                openDeliveryApp(sbn)
            } else {
                NotificationLogWriter.appendDebugEvent(
                    this,
                    "new_order_ignored",
                    "package" to sbn.packageName,
                    "autoOpenEnabled" to AppPrefs.isOrderAutoOpenEnabled(this),
                    "matchedRule" to isNewOrderNotification(sbn)
                )
            }
        }

        if (sbn.packageName in AppConstants.SUPPORTED_MUSIC_APPS) attachMusicControllers()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName in AppConstants.SUPPORTED_MUSIC_APPS) attachMusicControllers()
    }

    private fun isNewOrderNotification(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        val extras = n.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val channelId = if (Build.VERSION.SDK_INT >= 26) n.channelId.orEmpty() else ""

        return when (sbn.packageName) {
            AppConstants.PKG_BAEMIN ->
                channelId == AppConstants.BAEMIN_CHANNEL_NEW_ORDER &&
                    title == AppConstants.BAEMIN_TITLE_NEW_ORDER &&
                    text.contains(AppConstants.BAEMIN_TEXT_NEW_ORDER)

            AppConstants.PKG_COUPANG_EATS ->
                channelId == AppConstants.COUPANG_CHANNEL_NEW_ORDER &&
                    text.contains(AppConstants.COUPANG_TEXT_NEW_ORDER) &&
                    title.isNotBlank()

            else -> false
        }
    }

    private fun openDeliveryApp(sbn: StatusBarNotification) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastAutoOpenAt[sbn.key] ?: 0L
        if (now - previous < AppConstants.DELIVERY_AUTO_OPEN_DEDUPE_MS) {
            NotificationLogWriter.appendDebugEvent(
                this,
                "delivery_app_open_skipped",
                "package" to sbn.packageName,
                "reason" to "duplicate_suppression",
                "elapsedMs" to (now - previous)
            )
            return
        }
        lastAutoOpenAt[sbn.key] = now

        // 패키지 단위 중복 방지 — 동일 앱의 다른 키 알림이 짧은 간격으로 연속 도착할 때 차단
        val previousPkg = lastAutoOpenPkgAt[sbn.packageName] ?: 0L
        if (now - previousPkg < AppConstants.DELIVERY_AUTO_OPEN_DEDUPE_MS) {
            NotificationLogWriter.appendDebugEvent(
                this,
                "delivery_app_open_skipped",
                "package" to sbn.packageName,
                "reason" to "duplicate_suppression_pkg",
                "elapsedMs" to (now - previousPkg)
            )
            return
        }
        lastAutoOpenPkgAt[sbn.packageName] = now

        val contentIntent = sbn.notification.contentIntent
        val launchIntent = packageManager.getLaunchIntentForPackage(sbn.packageName)

        NotificationLogWriter.appendDebugEvent(
            this,
            "delivery_app_open_attempt",
            "package" to sbn.packageName,
            "hasContentIntent" to (contentIntent != null),
            "hasLaunchIntent" to (launchIntent != null)
        )

        // 알림음이 시작될 시간을 확보하기 위해 launchIntent를 지연 발동
        // (즉시 포그라운드 전환 시 Android가 해당 앱의 알림음을 억제하는 문제 방지)
        Handler(Looper.getMainLooper()).postDelayed({
            // 1단계: launchIntent로 포그라운드 전환
            // FLAG_ACTIVITY_REORDER_TO_FRONT: 이미 실행 중이면 기존 액티비티를 앞으로 → 콜드스타트 없음
            if (launchIntent != null) {
                try {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    startActivity(launchIntent)
                    AppPrefs.setLastAutoOpenSentAt(this, sbn.packageName, SystemClock.elapsedRealtime())
                    NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "launchIntent", "성공")
                    NotificationLogWriter.appendDebugEvent(
                        this,
                        "delivery_app_switch_result",
                        "package" to sbn.packageName,
                        "method" to "launchIntent",
                        "result" to "success"
                    )
                } catch (e: Exception) {
                    NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "launchIntent", "실패: ${e.javaClass.simpleName}: ${e.message}")
                    NotificationLogWriter.appendDebugEvent(
                        this,
                        "delivery_app_switch_result",
                        "package" to sbn.packageName,
                        "method" to "launchIntent",
                        "result" to "fail",
                        "error" to "${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            }

            // 2단계: contentIntent로 주문화면 딥링크 (수락/거절 화면으로 직접 이동)
            // launchIntent 발동 후 CONTENT_INTENT_DELAY_MS 대기 후 무조건 발동
            if (contentIntent != null) {
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        NotificationLogWriter.appendDebugEvent(
                            this,
                            "content_intent_info",
                            "package" to sbn.packageName,
                            "creatorPackage" to contentIntent.creatorPackage,
                            "creatorUid" to contentIntent.creatorUid
                        )
                        contentIntent.send()
                        NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "contentIntent", "성공")
                    } catch (_: PendingIntent.CanceledException) {
                        // 만료된 intent — 무시, launchIntent가 이미 전송됨
                    } catch (e: Exception) {
                        NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "contentIntent", "실패: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }, AppConstants.CONTENT_INTENT_DELAY_MS)
            }
        }, AppConstants.LAUNCH_INTENT_DELAY_MS)
    }

    /** SUPPORTED_MUSIC_APPS 전체의 MediaController를 최신 세션으로 갱신 */
    private fun attachMusicControllers() {
        try {
            val manager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, MusicNotificationListener::class.java)
            val activeSessions = manager.getActiveSessions(component)
                .filter { it.packageName in AppConstants.SUPPORTED_MUSIC_APPS }
                .associateBy { it.packageName }

            // 새로 연결되거나 세션 토큰이 바뀐 앱만 갱신
            for (pkg in AppConstants.SUPPORTED_MUSIC_APPS) {
                val newCtrl = activeSessions[pkg]
                val oldCtrl = controllers[pkg]

                if (oldCtrl?.sessionToken == newCtrl?.sessionToken) continue

                // 기존 콜백 해제
                oldCtrl?.let { registeredCallbacks[pkg]?.let(it::unregisterCallback) }

                if (newCtrl != null) {
                    val cb = makeCallback(pkg)
                    newCtrl.registerCallback(cb)
                    controllers[pkg] = newCtrl
                    registeredCallbacks[pkg] = cb
                } else {
                    controllers.remove(pkg)
                    registeredCallbacks.remove(pkg)
                }

                NotificationLogWriter.appendDebugEvent(
                    this, "music_controller_state",
                    "package" to pkg,
                    "result" to if (newCtrl != null) "attached" else "none",
                    "retryCount" to attachRetryCount
                )
            }

            // autoPaused 앱의 컨트롤러가 붙었으면 재개 시도
            val targetPkg = AppPrefs.activeMusicPackage(this).ifBlank { MusicSessionHelper.YOUTUBE_MUSIC }
            if (controllers.containsKey(targetPkg)) {
                attachRetryHandler.removeCallbacksAndMessages(null)
                attachRetryScheduled = false
                attachRetryCount = 0
                if (AppPrefs.isAutoPaused(this) &&
                    (AppPrefs.isResumePending(this) || !AppPrefs.isTargetActive(this))) {
                    NotificationLogWriter.appendDebugEvent(
                        this, "music_controller_resume_trigger",
                        "package" to targetPkg,
                        "reason" to "controller_attached",
                        "retryCount" to AppPrefs.resumeRetryCount(this)
                    )
                    MusicSessionHelper.resumeIfAutoPaused(this)
                }
            } else if (shouldRetryControllerAttach()) {
                scheduleControllerAttachRetry("attach_none")
            }
        } catch (_: SecurityException) {
            detachAllControllers()
            NotificationLogWriter.appendDebugEvent(
                this, "music_controller_state", "result" to "security_exception"
            )
            if (shouldRetryControllerAttach()) scheduleControllerAttachRetry("security_exception")
        }
    }

    private fun detachAllControllers() {
        for ((pkg, ctrl) in controllers) {
            registeredCallbacks[pkg]?.let(ctrl::unregisterCallback)
        }
        controllers.clear()
        registeredCallbacks.clear()
    }

    private fun shouldRetryControllerAttach(): Boolean {
        return AppPrefs.isAutoPaused(this) ||
            AppPrefs.isResumePending(this) ||
            AppPrefs.isTargetActive(this)
    }

    private fun scheduleControllerAttachRetry(reason: String) {
        if (attachRetryScheduled || attachRetryCount >= ATTACH_RETRY_MAX) return

        attachRetryScheduled = true
        attachRetryCount++
        NotificationLogWriter.appendDebugEvent(
            this, "music_controller_retry_scheduled",
            "reason" to reason,
            "retryCount" to attachRetryCount,
            "maxRetry" to ATTACH_RETRY_MAX
        )
        attachRetryHandler.postDelayed({
            attachRetryScheduled = false
            attachMusicControllers()
            val targetPkg = AppPrefs.activeMusicPackage(this).ifBlank { MusicSessionHelper.YOUTUBE_MUSIC }
            if (!controllers.containsKey(targetPkg)) {
                scheduleControllerAttachRetry("retry_still_none")
            } else {
                attachRetryCount = 0
            }
        }, ATTACH_RETRY_DELAY_MS)
    }
}
