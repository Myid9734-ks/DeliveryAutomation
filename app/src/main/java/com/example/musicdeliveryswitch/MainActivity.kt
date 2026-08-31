package com.example.musicdeliveryswitch

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.example.musicdeliveryswitch.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var accessibilityOpened = false
    private var notificationOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.switchNavi.isChecked = AppPrefs.isNaviEnabled(this)
        binding.switchOrderAutoOpen.isChecked = AppPrefs.isOrderAutoOpenEnabled(this)
        binding.switchMusic.isChecked = AppPrefs.isMusicEnabled(this)

        when (AppPrefs.selectedNavi(this)) {
            AppConstants.NAVI_KAKAONAVI -> binding.radioKakaoNavi.isChecked = true
            AppConstants.NAVI_KAKAOMAP -> binding.radioKakaoMap.isChecked = true
            AppConstants.NAVI_NAVER -> binding.radioNaver.isChecked = true
            else -> binding.radioTmap.isChecked = true
        }

        NotificationLogWriter.appendDebugEvent(
            this,
            "main_screen_opened",
            "selectedNavi" to AppPrefs.selectedNavi(this),
            "naviEnabled" to AppPrefs.isNaviEnabled(this),
            "orderAutoOpenEnabled" to AppPrefs.isOrderAutoOpenEnabled(this),
            "musicEnabled" to AppPrefs.isMusicEnabled(this)
        )

        binding.switchNavi.setOnCheckedChangeListener { _, v ->
            AppPrefs.setNaviEnabled(this, v)
            NotificationLogWriter.appendDebugEvent(
                this,
                "setting_changed",
                "key" to "navi_enabled",
                "value" to v,
                "source" to "switchNavi"
            )
            status()
        }

        binding.switchOrderAutoOpen.setOnCheckedChangeListener { _, v ->
            AppPrefs.setOrderAutoOpenEnabled(this, v)
            NotificationLogWriter.appendDebugEvent(
                this,
                "setting_changed",
                "key" to "order_auto_open_enabled",
                "value" to v,
                "source" to "switchOrderAutoOpen"
            )
            status()
        }

        binding.switchMusic.setOnCheckedChangeListener { _, v ->
            AppPrefs.setMusicEnabled(this, v)
            NotificationLogWriter.appendDebugEvent(
                this,
                "setting_changed",
                "key" to "music_enabled",
                "value" to v,
                "source" to "switchMusic"
            )
            if (!v) resetMusicState()
            status()
        }

        binding.radioNavis.setOnCheckedChangeListener { _, id ->
            val selected = when (id) {
                R.id.radioKakaoNavi -> AppConstants.NAVI_KAKAONAVI
                R.id.radioKakaoMap -> AppConstants.NAVI_KAKAOMAP
                R.id.radioNaver -> AppConstants.NAVI_NAVER
                else -> AppConstants.NAVI_TMAP
            }
            AppPrefs.setSelectedNavi(this, selected)
            NotificationLogWriter.appendDebugEvent(
                this,
                "setting_changed",
                "key" to "selected_navi",
                "value" to selected,
                "source" to "radioNavis"
            )
            status()
        }

        binding.buttonAccessibility.setOnClickListener {
            accessibilityOpened = true
            notificationOpened = false
            NotificationLogWriter.appendDebugEvent(
                this,
                "settings_button_clicked",
                "target" to "accessibility"
            )
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.buttonNotificationAccess.setOnClickListener {
            notificationOpened = true
            NotificationLogWriter.appendDebugEvent(
                this,
                "settings_button_clicked",
                "target" to "notification_listener"
            )
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        status()
        binding.root.post { permissions() }
    }

    private fun resetMusicState() {
        NotificationLogWriter.appendAutoOpenResult(
            this,
            MusicSessionHelper.YOUTUBE_MUSIC,
            "state_reset",
            "music_toggle_off"
        )
        AppPrefs.setTargetActive(this, false)
        AppPrefs.setAutoPaused(this, false)
        AppPrefs.setAutoPauseAt(this, 0L)
        AppPrefs.setResumePending(this, false)
        AppPrefs.setResumeRequestedAt(this, 0L)
    }

    private fun permissions() {
        if (!access()) {
            NotificationLogWriter.appendDebugEvent(
                this,
                "permission_check_failed",
                "permission" to "accessibility",
                "action" to "open_settings"
            )
            if (!accessibilityOpened) {
                accessibilityOpened = true
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            return
        }

        if (!notifyOK()) {
            NotificationLogWriter.appendDebugEvent(
                this,
                "permission_check_failed",
                "permission" to "notification_listener",
                "action" to "open_settings"
            )
            if (!notificationOpened) {
                notificationOpened = true
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            return
        }

        NotificationLogWriter.appendDebugEvent(
            this,
            "permission_check_ok",
            "accessibility" to access(),
            "notification_listener" to notifyOK()
        )
    }

    private fun status() {
        val n = when (AppPrefs.selectedNavi(this)) {
            AppConstants.NAVI_KAKAONAVI -> "KakaoNavi"
            AppConstants.NAVI_KAKAOMAP -> "KakaoMap"
            AppConstants.NAVI_NAVER -> "NaverMap"
            else -> "TMAP"
        }
        binding.textStatus.text = buildString {
            append("Navi: ${if (AppPrefs.isNaviEnabled(this@MainActivity)) "ON" else "OFF"} ($n)\n")
            append("Order auto open: ${if (AppPrefs.isOrderAutoOpenEnabled(this@MainActivity)) "ON" else "OFF"}\n")
            append("Music: ${if (AppPrefs.isMusicEnabled(this@MainActivity)) "ON" else "OFF"}\n")
            append("Accessibility: ${if (access()) "GRANTED" else "NEEDED"}\n")
            append("Notification access: ${if (notifyOK()) "GRANTED" else "NEEDED"}")
        }
        NotificationLogWriter.appendDebugEvent(
            this,
            "status_refresh",
            "selectedNavi" to AppPrefs.selectedNavi(this),
            "naviEnabled" to AppPrefs.isNaviEnabled(this),
            "orderAutoOpenEnabled" to AppPrefs.isOrderAutoOpenEnabled(this),
            "musicEnabled" to AppPrefs.isMusicEnabled(this),
            "accessibilityGranted" to access(),
            "notificationGranted" to notifyOK()
        )
    }

    private fun access(): Boolean {
        val e = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val q = ComponentName(this, ForegroundAppAccessibilityService::class.java).flattenToString()
        return e.split(':').any { it.equals(q, true) }
    }

    private fun notifyOK(): Boolean {
        val e = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val q = ComponentName(this, MusicNotificationListener::class.java).flattenToString()
        return e.split(':').any { it.equals(q, true) }
    }
}
