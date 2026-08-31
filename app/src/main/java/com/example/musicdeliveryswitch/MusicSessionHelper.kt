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
    const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"
    private const val RESUME_RETRY_MAX = 5
    private const val RESUME_RETRY_DELAY_MS = 400L
    private val retryHandler = Handler(Looper.getMainLooper())

    private fun youtubeController(context: Context): MediaController? {
        return try {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(context, MusicNotificationListener::class.java)
            manager.getActiveSessions(component).firstOrNull { it.packageName == YOUTUBE_MUSIC }
        } catch (_: SecurityException) {
            null
        }
    }

    fun isYoutubeMusicPlaying(context: Context): Boolean {
        return youtubeController(context)?.playbackState?.state == PlaybackState.STATE_PLAYING
    }

    fun pauseYoutubeMusic(context: Context): Boolean {
        val controller = youtubeController(context) ?: return false
        val state = controller.playbackState?.state
        if (state != PlaybackState.STATE_PLAYING) {
            NotificationLogWriter.appendAutoOpenResult(
                context,
                YOUTUBE_MUSIC,
                "pause_request",
                "skipped:not_playing:$state"
            )
            return false
        }

        AppPrefs.setAutoPaused(context, true)
        AppPrefs.setAutoPauseAt(context, SystemClock.elapsedRealtime())
        AppPrefs.setResumePending(context, false)
        AppPrefs.setResumeRequestedAt(context, 0L)
        NotificationLogWriter.appendAutoOpenResult(
            context,
            YOUTUBE_MUSIC,
            "pause_request",
            "sent:playing"
        )
        controller.transportControls.pause()
        return true
    }

    fun resumeYoutubeMusicIfAutoPaused(context: Context) {
        if (!AppPrefs.isAutoPaused(context)) {
            AppPrefs.setResumeRetryCount(context, 0)
            AppPrefs.setResumeRetryScheduled(context, false)
            NotificationLogWriter.appendAutoOpenResult(
                context,
                YOUTUBE_MUSIC,
                "resume_request",
                "skipped:not_auto_paused"
            )
            return
        }

        val controller = youtubeController(context)
        if (controller == null) {
            val retryCount = AppPrefs.resumeRetryCount(context)
            NotificationLogWriter.appendAutoOpenResult(
                context,
                YOUTUBE_MUSIC,
                "resume_request",
                "sent:no_controller"
            )
            NotificationLogWriter.appendDebugEvent(
                context,
                "youtube_resume_retry",
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
                        resumeYoutubeMusicIfAutoPaused(context)
                    } else {
                        AppPrefs.setResumeRetryCount(context, 0)
                    }
                }, RESUME_RETRY_DELAY_MS)
            } else {
                AppPrefs.setResumeRetryCount(context, 0)
            }
            return
        }

        AppPrefs.setResumeRetryCount(context, 0)
        AppPrefs.setResumeRetryScheduled(context, false)
        AppPrefs.setResumePending(context, true)
        AppPrefs.setResumeRequestedAt(context, SystemClock.elapsedRealtime())
        NotificationLogWriter.appendAutoOpenResult(
            context,
            YOUTUBE_MUSIC,
            "resume_request",
            "sent:controller_found"
        )
        controller.transportControls.play()
    }
}
