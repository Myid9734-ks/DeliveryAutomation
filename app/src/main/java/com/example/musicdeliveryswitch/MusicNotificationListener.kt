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

    private var controller: MediaController? = null
    private val lastAutoOpenAt = mutableMapOf<String, Long>()

    // 컨트롤러 부착 재시도 — YT Music 세션이 늦게 열리는 경우를 대비
    private val attachRetryHandler = Handler(Looper.getMainLooper())
    private var attachRetryScheduled = false
    private var attachRetryCount = 0

    companion object {
        private const val ATTACH_RETRY_MAX = 6
        private const val ATTACH_RETRY_DELAY_MS = 350L
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            NotificationLogWriter.appendAutoOpenResult(
                this@MusicNotificationListener,
                MusicSessionHelper.YOUTUBE_MUSIC,
                "playback_state_changed",
                "state=${state?.state ?: -1}, autoPaused=${AppPrefs.isAutoPaused(this@MusicNotificationListener)}, targetActive=${AppPrefs.isTargetActive(this@MusicNotificationListener)}, resumePending=${AppPrefs.isResumePending(this@MusicNotificationListener)}"
            )
            if (state?.state != PlaybackState.STATE_PLAYING) return
            if (!AppPrefs.isAutoPaused(this@MusicNotificationListener)) return

            val resumeAt = AppPrefs.resumeRequestedAt(this@MusicNotificationListener)
            val elapsed = SystemClock.elapsedRealtime() - if (resumeAt > 0L) resumeAt else AppPrefs.autoPauseAt(this@MusicNotificationListener)
            NotificationLogWriter.appendAutoOpenResult(
                this@MusicNotificationListener,
                MusicSessionHelper.YOUTUBE_MUSIC,
                "auto_resume_complete",
                "playing_after=${elapsed}ms"
            )
            AppPrefs.setAutoPaused(this@MusicNotificationListener, false)
            AppPrefs.setAutoPauseAt(this@MusicNotificationListener, 0L)
            AppPrefs.setResumePending(this@MusicNotificationListener, false)
            AppPrefs.setResumeRequestedAt(this@MusicNotificationListener, 0L)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        attachYoutubeController()
    }

    override fun onListenerDisconnected() {
        controller?.unregisterCallback(callback)
        controller = null
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

        if (sbn.packageName == MusicSessionHelper.YOUTUBE_MUSIC) attachYoutubeController()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName == MusicSessionHelper.YOUTUBE_MUSIC) attachYoutubeController()
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
        val previous = lastAutoOpenAt[sbn.packageName] ?: 0L
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
        lastAutoOpenAt[sbn.packageName] = now

        NotificationLogWriter.appendDebugEvent(
            this,
            "delivery_app_open_attempt",
            "package" to sbn.packageName,
            "hasContentIntent" to (sbn.notification.contentIntent != null),
            "hasLaunchIntent" to (packageManager.getLaunchIntentForPackage(sbn.packageName) != null)
        )

        try {
            val contentIntent = sbn.notification.contentIntent
            if (contentIntent == null) {
                NotificationLogWriter.appendDebugEvent(
                    this,
                    "delivery_app_open_fallback",
                    "package" to sbn.packageName,
                    "from" to "contentIntent",
                    "reason" to "missing"
                )
            } else {
                contentIntent.send()
                NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "contentIntent", "성공")
                NotificationLogWriter.appendDebugEvent(
                    this,
                    "delivery_app_switch_result",
                    "package" to sbn.packageName,
                    "method" to "contentIntent",
                    "result" to "success"
                )
                return
            }
        } catch (_: PendingIntent.CanceledException) {
            NotificationLogWriter.appendDebugEvent(
                this,
                "delivery_app_open_fallback",
                "package" to sbn.packageName,
                "from" to "contentIntent",
                "reason" to "canceled"
            )
        } catch (e: Exception) {
            NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "contentIntent", "실패: ${e.javaClass.simpleName}: ${e.message}")
            NotificationLogWriter.appendDebugEvent(
                this,
                "delivery_app_open_fallback",
                "package" to sbn.packageName,
                "from" to "contentIntent",
                "reason" to "${e.javaClass.simpleName}: ${e.message}"
            )
        }

        try {
            val launch = packageManager.getLaunchIntentForPackage(sbn.packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(launch)
                NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "launchIntent", "성공")
                NotificationLogWriter.appendDebugEvent(
                    this,
                    "delivery_app_switch_result",
                    "package" to sbn.packageName,
                    "method" to "launchIntent",
                    "result" to "success"
                )
            } else {
                NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "launchIntent", "실패: 실행 Intent 없음")
                NotificationLogWriter.appendDebugEvent(
                    this,
                    "delivery_app_switch_result",
                    "package" to sbn.packageName,
                    "method" to "launchIntent",
                    "result" to "missing_launch_intent"
                )
            }
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

    private fun attachYoutubeController() {
        try {
            val manager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, MusicNotificationListener::class.java)
            val newController = manager.getActiveSessions(component)
                .firstOrNull { it.packageName == MusicSessionHelper.YOUTUBE_MUSIC }

            if (controller?.sessionToken == newController?.sessionToken) return

            controller?.unregisterCallback(callback)
            controller = newController
            controller?.registerCallback(callback)

            NotificationLogWriter.appendAutoOpenResult(
                this,
                MusicSessionHelper.YOUTUBE_MUSIC,
                "controller_attach",
                if (controller == null) "none" else "attached"
            )
            NotificationLogWriter.appendDebugEvent(
                this,
                "youtube_controller_state",
                "result" to if (controller == null) "none" else "attached",
                "retryCount" to attachRetryCount
            )

            if (controller != null) {
                attachRetryHandler.removeCallbacksAndMessages(null)
                attachRetryScheduled = false
                attachRetryCount = 0
                // 컨트롤러가 연결됐고 재개 대기 중이면 즉시 재개 시도
                if (AppPrefs.isAutoPaused(this) &&
                    (AppPrefs.isResumePending(this) || !AppPrefs.isTargetActive(this))) {
                    NotificationLogWriter.appendDebugEvent(
                        this,
                        "youtube_controller_resume_trigger",
                        "reason" to "controller_attached",
                        "retryCount" to AppPrefs.resumeRetryCount(this)
                    )
                    MusicSessionHelper.resumeYoutubeMusicIfAutoPaused(this)
                }
            } else if (shouldRetryControllerAttach()) {
                scheduleControllerAttachRetry("attach_none")
            }
        } catch (_: SecurityException) {
            controller = null
            NotificationLogWriter.appendAutoOpenResult(
                this, MusicSessionHelper.YOUTUBE_MUSIC, "controller_attach", "security_exception"
            )
            NotificationLogWriter.appendDebugEvent(
                this, "youtube_controller_state", "result" to "security_exception"
            )
            if (shouldRetryControllerAttach()) scheduleControllerAttachRetry("security_exception")
        }
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
            this,
            "youtube_controller_retry_scheduled",
            "reason" to reason,
            "retryCount" to attachRetryCount,
            "maxRetry" to ATTACH_RETRY_MAX
        )
        attachRetryHandler.postDelayed({
            attachRetryScheduled = false
            attachYoutubeController()
            if (controller == null) {
                scheduleControllerAttachRetry("retry_still_none")
            } else {
                attachRetryCount = 0
            }
        }, ATTACH_RETRY_DELAY_MS)
    }
}
