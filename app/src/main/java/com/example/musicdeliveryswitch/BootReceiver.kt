package com.example.musicdeliveryswitch

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        // 재부팅 전의 일시적인 감지 상태는 초기화한다.
        NotificationLogWriter.appendAutoOpenResult(context, MusicSessionHelper.YOUTUBE_MUSIC, "state_reset", "boot_completed")
        AppPrefs.setTargetActive(context, false)
        AppPrefs.setAutoPaused(context, false)
        AppPrefs.setAutoPauseAt(context, 0L)
        AppPrefs.setResumePending(context, false)
        AppPrefs.setResumeRequestedAt(context, 0L)

        // 음악 제어뿐 아니라 배민/쿠팡 알림 로그 수집에도 사용하므로 항상 재연결을 요청한다.
        try {
            NotificationListenerService.requestRebind(
                ComponentName(context, MusicNotificationListener::class.java)
            )
        } catch (_: Exception) {
            // 기기/OS가 자동 재연결을 처리하는 경우 별도 동작이 필요 없다.
        }
    }
}
