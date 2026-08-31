package com.example.musicdeliveryswitch

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock

object MusicSessionHelper {
    const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"

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
            NotificationLogWriter.appendAutoOpenResult(
                context,
                YOUTUBE_MUSIC,
                "resume_request",
                "sent:no_controller"
            )
            return
        }

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
