package com.example.musicdeliveryswitch

import android.content.Context

object AppPrefs {

    private const val FILE = "delivery_automation_prefs"

    private const val KEY_MUSIC_ENABLED = "music_enabled"
    private const val KEY_NAVI_ENABLED = "navi_enabled"
    private const val KEY_ORDER_AUTO_OPEN_ENABLED = "order_auto_open_enabled"
    private const val KEY_SELECTED_NAVI = "selected_navi"
    private const val KEY_AUTO_PAUSED = "auto_paused"
    private const val KEY_RESUME_PENDING = "resume_pending"
    private const val KEY_RESUME_REQUESTED_AT = "resume_requested_at"
    private const val KEY_RESUME_RETRY_COUNT = "resume_retry_count"
    private const val KEY_RESUME_RETRY_SCHEDULED = "resume_retry_scheduled"
    private const val KEY_TARGET_ACTIVE = "target_active"
    private const val KEY_NAV_SESSION_ACTIVE = "nav_session_active"
    private const val KEY_AUTO_PAUSE_AT = "auto_pause_at"
    private const val KEY_LAST_FOREGROUND_PACKAGE = "last_foreground_package"
    private const val KEY_LAST_DELIVERY_DESTINATION_TEXT = "last_delivery_destination_text"
    private const val KEY_LAST_DELIVERY_DESTINATION_AT = "last_delivery_destination_at"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isMusicEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_MUSIC_ENABLED, true)

    fun setMusicEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_MUSIC_ENABLED, value).apply()

    fun isNaviEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_NAVI_ENABLED, true)

    fun setNaviEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_NAVI_ENABLED, value).apply()

    fun isOrderAutoOpenEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ORDER_AUTO_OPEN_ENABLED, true)

    fun setOrderAutoOpenEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_ORDER_AUTO_OPEN_ENABLED, value).apply()

    fun selectedNavi(context: Context): String = prefs(context).getString(KEY_SELECTED_NAVI, AppConstants.NAVI_TMAP) ?: AppConstants.NAVI_TMAP

    fun setSelectedNavi(context: Context, value: String) = prefs(context).edit().putString(KEY_SELECTED_NAVI, value).apply()

    fun isAutoPaused(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_PAUSED, false)

    fun setAutoPaused(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_AUTO_PAUSED, value).apply()

    fun isResumePending(context: Context): Boolean = prefs(context).getBoolean(KEY_RESUME_PENDING, false)

    fun setResumePending(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_RESUME_PENDING, value).apply()

    fun resumeRequestedAt(context: Context): Long = prefs(context).getLong(KEY_RESUME_REQUESTED_AT, 0L)

    fun setResumeRequestedAt(context: Context, value: Long) = prefs(context).edit().putLong(KEY_RESUME_REQUESTED_AT, value).apply()

    fun resumeRetryCount(context: Context): Int = prefs(context).getInt(KEY_RESUME_RETRY_COUNT, 0)

    fun setResumeRetryCount(context: Context, value: Int) = prefs(context).edit().putInt(KEY_RESUME_RETRY_COUNT, value).apply()

    fun isResumeRetryScheduled(context: Context): Boolean = prefs(context).getBoolean(KEY_RESUME_RETRY_SCHEDULED, false)

    fun setResumeRetryScheduled(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_RESUME_RETRY_SCHEDULED, value).apply()

    fun isTargetActive(context: Context): Boolean = prefs(context).getBoolean(KEY_TARGET_ACTIVE, false)

    fun setTargetActive(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_TARGET_ACTIVE, value).apply()

    fun isNavSessionActive(context: Context): Boolean = prefs(context).getBoolean(KEY_NAV_SESSION_ACTIVE, false)

    fun setNavSessionActive(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_NAV_SESSION_ACTIVE, value).apply()

    fun autoPauseAt(context: Context): Long = prefs(context).getLong(KEY_AUTO_PAUSE_AT, 0L)

    fun setAutoPauseAt(context: Context, value: Long) = prefs(context).edit().putLong(KEY_AUTO_PAUSE_AT, value).apply()

    fun lastForegroundPackage(context: Context): String = prefs(context).getString(KEY_LAST_FOREGROUND_PACKAGE, "") ?: ""

    fun setLastForegroundPackage(context: Context, value: String) = prefs(context).edit().putString(KEY_LAST_FOREGROUND_PACKAGE, value).apply()

    fun lastDeliveryDestinationText(context: Context): String = prefs(context).getString(KEY_LAST_DELIVERY_DESTINATION_TEXT, "") ?: ""

    fun setLastDeliveryDestinationText(context: Context, value: String) = prefs(context).edit().putString(KEY_LAST_DELIVERY_DESTINATION_TEXT, value).apply()

    fun lastDeliveryDestinationAt(context: Context): Long = prefs(context).getLong(KEY_LAST_DELIVERY_DESTINATION_AT, 0L)

    fun setLastDeliveryDestinationAt(context: Context, value: Long) = prefs(context).edit().putLong(KEY_LAST_DELIVERY_DESTINATION_AT, value).apply()
}
