package com.example.musicdeliveryswitch

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

object MusicSessionHelper {

    // 하위 호환 — 기존 코드에서 YOUTUBE_MUSIC 상수를 직접 참조하는 곳을 위해 유지
    const val YOUTUBE_MUSIC = AppConstants.PKG_YOUTUBE_MUSIC

    private const val RESUME_RETRY_MAX = 5
    private const val RESUME_RETRY_DELAY_MS = 400L
    private val retryHandler = Handler(Looper.getMainLooper())

    // ── 컨트롤러 조회 ──────────────────────────────────────────────

    private fun getController(context: Context, packageName: String): MediaController? {
        return try {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(context, MusicNotificationListener::class.java)
            manager.getActiveSessions(component).firstOrNull { it.packageName == packageName }
        } catch (_: SecurityException) {
            null
        }
    }

    /** SUPPORTED_MUSIC_APPS 중 현재 재생 중인 앱의 컨트롤러를 반환 */
    private fun findPlayingController(context: Context): MediaController? {
        return try {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(context, MusicNotificationListener::class.java)
            manager.getActiveSessions(component)
                .firstOrNull { ctrl ->
                    ctrl.packageName in AppConstants.SUPPORTED_MUSIC_APPS &&
                        ctrl.playbackState?.state == PlaybackState.STATE_PLAYING
                }
        } catch (_: SecurityException) {
            null
        }
    }

    // ── 재생 상태 확인 ──────────────────────────────────────────────

    /** 특정 앱이 현재 재생 중인지 확인 */
    fun isPlaying(context: Context, packageName: String): Boolean =
        getController(context, packageName)?.playbackState?.state == PlaybackState.STATE_PLAYING

    /** 특정 앱이 현재 일시정지 상태인지 확인 */
    fun isPaused(context: Context, packageName: String): Boolean =
        getController(context, packageName)?.playbackState?.state == PlaybackState.STATE_PAUSED

    /** SUPPORTED_MUSIC_APPS 중 하나라도 재생 중이면 true */
    fun isAnyMusicPlaying(context: Context): Boolean =
        findPlayingController(context) != null

    // ── 일시정지 ──────────────────────────────────────────────────

    /**
     * 현재 재생 중인 음악 앱을 찾아 일시정지.
     * 어떤 앱을 멈췄는지 AppPrefs.activeMusicPackage에 저장 → 재개 시 정확한 앱 복원.
     */
    fun pauseActivePlayer(context: Context): Boolean {
        val ctrl = findPlayingController(context) ?: return false
        val packageName = ctrl.packageName

        AppPrefs.setAutoPaused(context, true)
        AppPrefs.setAutoPauseAt(context, SystemClock.elapsedRealtime())
        AppPrefs.setResumePending(context, false)
        AppPrefs.setResumeRequestedAt(context, 0L)
        AppPrefs.setActiveMusicPackage(context, packageName)

        NotificationLogWriter.appendAutoOpenResult(
            context, packageName, "pause_request", "sent:playing"
        )
        try {
            ctrl.transportControls.pause()
        } catch (e: Exception) {
            NotificationLogWriter.appendDebugEvent(
                context, "music_pause_failed",
                "package" to packageName,
                "error" to "${e.javaClass.simpleName}: ${e.message}"
            )
        }
        return true
    }

    // ── 재개 ──────────────────────────────────────────────────────

    /**
     * autoPaused 상태면 AppPrefs.activeMusicPackage에 저장된 앱을 재개.
     * 컨트롤러를 찾지 못하면 최대 RESUME_RETRY_MAX회 재시도.
     */
    fun resumeIfAutoPaused(context: Context) {
        if (!AppPrefs.isAutoPaused(context)) {
            AppPrefs.setResumeRetryCount(context, 0)
            AppPrefs.setResumeRetryScheduled(context, false)
            NotificationLogWriter.appendAutoOpenResult(
                context, AppPrefs.activeMusicPackage(context).ifBlank { YOUTUBE_MUSIC },
                "resume_request", "skipped:not_auto_paused"
            )
            return
        }

        val targetPackage = AppPrefs.activeMusicPackage(context).ifBlank { YOUTUBE_MUSIC }
        val ctrl = getController(context, targetPackage)

        if (ctrl == null) {
            val retryCount = AppPrefs.resumeRetryCount(context)
            NotificationLogWriter.appendAutoOpenResult(
                context, targetPackage, "resume_request", "sent:no_controller"
            )
            NotificationLogWriter.appendDebugEvent(
                context, "music_resume_retry",
                "package" to targetPackage,
                "result" to "no_controller",
                "retryCount" to retryCount,
                "maxRetry" to RESUME_RETRY_MAX
            )
            if (retryCount < RESUME_RETRY_MAX && !AppPrefs.isResumeRetryScheduled(context)) {
                AppPrefs.setResumeRetryCount(context, retryCount + 1)
                AppPrefs.setResumeRetryScheduled(context, true)
                retryHandler.postDelayed({
                    AppPrefs.setResumeRetryScheduled(context, false)
                    if (AppPrefs.isAutoPaused(context) && !AppPrefs.isTargetActive(context)) {
                        resumeIfAutoPaused(context)
                    } else {
                        AppPrefs.setResumeRetryCount(context, 0)
                    }
                }, RESUME_RETRY_DELAY_MS)
            } else {
                // 최대 재시도 초과 — autoPaused 플래그를 초기화해 영구 stuck 방지
                AppPrefs.setResumeRetryCount(context, 0)
                AppPrefs.setResumeRetryScheduled(context, false)
                AppPrefs.setAutoPaused(context, false)
                AppPrefs.clearActiveMusicPackage(context)
                NotificationLogWriter.appendDebugEvent(
                    context, "music_resume_retry_exhausted",
                    "package" to targetPackage,
                    "result" to "auto_paused_cleared"
                )
            }
            return
        }

        AppPrefs.setResumeRetryCount(context, 0)
        AppPrefs.setResumeRetryScheduled(context, false)
        AppPrefs.setResumePending(context, true)
        AppPrefs.setResumeRequestedAt(context, SystemClock.elapsedRealtime())
        NotificationLogWriter.appendAutoOpenResult(
            context, targetPackage, "resume_request", "sent:controller_found"
        )
        try {
            ctrl.transportControls.play()
        } catch (e: Exception) {
            NotificationLogWriter.appendDebugEvent(
                context, "music_resume_failed",
                "package" to targetPackage,
                "error" to "${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    // ── 하위 호환 래퍼 (기존 호출부 rename 전까지 유지) ─────────────

    @Deprecated("pauseActivePlayer() 사용", ReplaceWith("pauseActivePlayer(context)"))
    fun pauseYoutubeMusic(context: Context): Boolean = pauseActivePlayer(context)

    @Deprecated("resumeIfAutoPaused() 사용", ReplaceWith("resumeIfAutoPaused(context)"))
    fun resumeYoutubeMusicIfAutoPaused(context: Context) = resumeIfAutoPaused(context)

    @Deprecated("isPlaying(context, packageName) 사용", ReplaceWith("isPlaying(context, YOUTUBE_MUSIC)"))
    fun isYoutubeMusicPlaying(context: Context): Boolean = isAnyMusicPlaying(context)

    @Deprecated("isPaused(context, packageName) 사용", ReplaceWith("isPaused(context, YOUTUBE_MUSIC)"))
    fun isYoutubeMusicPaused(context: Context): Boolean =
        isPaused(context, AppPrefs.activeMusicPackage(context).ifBlank { YOUTUBE_MUSIC })
}
