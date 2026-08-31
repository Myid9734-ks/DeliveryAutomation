package com.example.musicdeliveryswitch

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MusicNotificationListener : NotificationListenerService() {

    private var controller: MediaController? = null
    private val lastAutoOpenAt = mutableMapOf<String, Long>()

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

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        if (NotificationLogWriter.isDeliveryPackage(sbn.packageName)) {
            NotificationLogWriter.append(this, sbn)

            // 실제 신규 주문/신규 배달 알림만 앱 화면으로 전환한다.
            if (AppPrefs.isOrderAutoOpenEnabled(this) && isNewOrderNotification(sbn)) {
                openDeliveryApp(sbn)
            }
        }

        if (sbn.packageName == MusicSessionHelper.YOUTUBE_MUSIC) attachYoutubeController()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName == MusicSessionHelper.YOUTUBE_MUSIC) attachYoutubeController()
    }

    override fun onListenerDisconnected() {
        controller?.unregisterCallback(callback)
        controller = null
        super.onListenerDisconnected()
    }

    private fun isNewOrderNotification(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        val extras = n.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val channelId = if (Build.VERSION.SDK_INT >= 26) n.channelId.orEmpty() else ""

        return when (sbn.packageName) {
            "com.woowahan.bros" ->
                channelId == "BROS_DELIVERY_ALLOCATION_NOTI" &&
                    title == "신규배달" &&
                    text.contains("새로운 배달이 배정되었습니다")

            "com.coupang.mobile.eats.courier" ->
                channelId == "COURIER_ASSIGNMENT" &&
                    text.contains("주문을 수락해주세요") &&
                    title.isNotBlank()

            else -> false
        }
    }

    private fun openDeliveryApp(sbn: StatusBarNotification) {
        // 같은 주문 알림이 시스템 그룹 알림 등으로 짧은 시간 안에 중복 수신돼도 한 번만 연다.
        val now = SystemClock.elapsedRealtime()
        val previous = lastAutoOpenAt[sbn.packageName] ?: 0L
        if (now - previous < 2000L) return
        lastAutoOpenAt[sbn.packageName] = now

        // 알림을 직접 눌렀을 때와 같은 PendingIntent를 우선 사용한다.
        // 이것이 일반 launchIntent보다 백그라운드 실행 제한에 덜 걸리고 정확한 주문 화면을 열 가능성이 높다.
        try {
            sbn.notification.contentIntent?.send()
            NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "contentIntent", "성공")
            return
        } catch (_: PendingIntent.CanceledException) {
            // 아래 launchIntent로 재시도
        } catch (e: Exception) {
            NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "contentIntent", "실패: ${e.javaClass.simpleName}: ${e.message}")
        }

        try {
            val launch = packageManager.getLaunchIntentForPackage(sbn.packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(launch)
                NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "launchIntent", "성공")
            } else {
                NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "launchIntent", "실패: 실행 Intent 없음")
            }
        } catch (e: Exception) {
            NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "launchIntent", "실패: ${e.javaClass.simpleName}: ${e.message}")
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
        } catch (_: SecurityException) {
            controller = null
            NotificationLogWriter.appendAutoOpenResult(
                this,
                MusicSessionHelper.YOUTUBE_MUSIC,
                "controller_attach",
                "security_exception"
            )
        }
    }
}
