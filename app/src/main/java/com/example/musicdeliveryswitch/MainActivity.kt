package com.example.musicdeliveryswitch

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.example.musicdeliveryswitch.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private var ao = false
    private var no = false

    override fun onCreate(x: Bundle?) {
        super.onCreate(x)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.switchNavi.isChecked = AppPrefs.isNaviEnabled(this)
        b.switchOrderAutoOpen.isChecked = AppPrefs.isOrderAutoOpenEnabled(this)
        b.switchMusic.isChecked = AppPrefs.isMusicEnabled(this)

        when (AppPrefs.selectedNavi(this)) {
            "KAKAONAVI" -> b.radioKakaoNavi.isChecked = true
            "KAKAOMAP" -> b.radioKakaoMap.isChecked = true
            "NAVER" -> b.radioNaver.isChecked = true
            else -> b.radioTmap.isChecked = true
        }

        NotificationLogWriter.appendDebugEvent(
            this,
            "main_screen_opened",
            "selectedNavi" to AppPrefs.selectedNavi(this),
            "naviEnabled" to AppPrefs.isNaviEnabled(this),
            "orderAutoOpenEnabled" to AppPrefs.isOrderAutoOpenEnabled(this),
            "musicEnabled" to AppPrefs.isMusicEnabled(this)
        )

        b.switchNavi.setOnCheckedChangeListener { _, v ->
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

        b.switchOrderAutoOpen.setOnCheckedChangeListener { _, v ->
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

        b.switchMusic.setOnCheckedChangeListener { _, v ->
            AppPrefs.setMusicEnabled(this, v)
            NotificationLogWriter.appendDebugEvent(
                this,
                "setting_changed",
                "key" to "music_enabled",
                "value" to v,
                "source" to "switchMusic"
            )
            if (!v) clear()
            status()
        }

        b.radioNavis.setOnCheckedChangeListener { _, id ->
            val selected = when (id) {
                R.id.radioKakaoNavi -> "KAKAONAVI"
                R.id.radioKakaoMap -> "KAKAOMAP"
                R.id.radioNaver -> "NAVER"
                else -> "TMAP"
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

        b.buttonAccessibility.setOnClickListener {
            ao = true
            no = false
            NotificationLogWriter.appendDebugEvent(
                this,
                "settings_button_clicked",
                "target" to "accessibility"
            )
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        b.buttonNotificationAccess.setOnClickListener {
            no = true
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
        b.root.post { permissions() }
    }

    private fun clear() {
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
            if (!ao) {
                ao = true
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
            if (!no) {
                no = true
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
            "KAKAONAVI" -> "KakaoNavi"
            "KAKAOMAP" -> "KakaoMap"
            "NAVER" -> "NaverMap"
            else -> "TMAP"
        }
        b.textStatus.text = buildString {
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
