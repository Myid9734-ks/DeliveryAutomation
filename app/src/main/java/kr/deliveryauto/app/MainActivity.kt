package kr.deliveryauto.app

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kr.deliveryauto.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var billingManager: BillingManager? = null
    private var accessibilityOpened = false
    private var notificationOpened = false
    private var batteryOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 체험판 등록/확인
        TrialManager.registerOrVerify(this) { updateTrialUI() }

        // 결제 관리자 초기화
        billingManager = BillingManager(this) { success ->
            if (success) updateTrialUI()
        }
        billingManager?.startConnection()

        binding.buttonPurchase.setOnClickListener {
            billingManager?.launchPurchase()
        }

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

        binding.buttonShareLog.setOnClickListener { shareLog() }

        binding.buttonBatteryOptimization.setOnClickListener {
            NotificationLogWriter.appendDebugEvent(
                this,
                "settings_button_clicked",
                "target" to "battery_optimization"
            )
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
                batteryOpened = true
            } catch (e: Exception) {
                NotificationLogWriter.appendDebugEvent(
                    this,
                    "settings_open_failed",
                    "target" to "battery_optimization",
                    "error" to (e.message ?: "unknown")
                )
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    batteryOpened = true
                } catch (e2: Exception) {
                    NotificationLogWriter.appendDebugEvent(
                        this,
                        "settings_open_failed_fallback",
                        "target" to "battery_optimization",
                        "error" to (e2.message ?: "unknown")
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateTrialUI()
        status()
        binding.root.post { permissions() }
    }

    override fun onDestroy() {
        billingManager?.endConnection()
        super.onDestroy()
    }

    private fun updateTrialUI() {
        val purchased = AppPrefs.isPurchased(this)
        val unlocked = AppPrefs.isFeatureUnlocked(this)

        if (purchased) {
            binding.cardTrial.visibility = View.GONE
        } else {
            binding.cardTrial.visibility = View.VISIBLE
            val remaining = AppPrefs.trialRemainingDays(this)
            if (unlocked) {
                binding.textTrialStatus.text = "체험판 (${remaining}일 남음)"
                binding.textTrialDetail.text = "체험 기간이 끝나면 자동화 기능이 중단됩니다."
                binding.buttonPurchase.visibility = View.VISIBLE
                binding.buttonPurchase.text = billingManager?.getFormattedPrice()?.let {
                    "정식 버전 구매 ($it)"
                } ?: "정식 버전 구매"
            } else {
                binding.textTrialStatus.text = "체험 기간 만료"
                binding.textTrialDetail.text = "정식 버전을 구매하면 모든 기능을 영구적으로 사용할 수 있습니다."
                binding.buttonPurchase.visibility = View.VISIBLE
                binding.buttonPurchase.text = billingManager?.getFormattedPrice()?.let {
                    "정식 버전 구매 ($it)"
                } ?: "정식 버전 구매"
            }
        }

        // 만료 시 스위치 비활성화
        setControlsEnabled(unlocked || purchased)
    }

    private fun setControlsEnabled(enabled: Boolean) {
        binding.switchNavi.isEnabled = enabled
        binding.switchOrderAutoOpen.isEnabled = enabled
        binding.switchMusic.isEnabled = enabled
        binding.radioTmap.isEnabled = enabled
        binding.radioKakaoNavi.isEnabled = enabled
        binding.radioKakaoMap.isEnabled = enabled
        binding.radioNaver.isEnabled = enabled
    }

    private fun shareLog() {
        val uri = NotificationLogWriter.getLogUri(this) ?: run {
            Toast.makeText(this, "로그 파일이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.log_share_warning_title)
            .setMessage(R.string.log_share_warning_message)
            .setPositiveButton(R.string.log_share_confirm) { _, _ -> sendLogEmail(uri) }
            .setNegativeButton(R.string.log_share_cancel, null)
            .show()
    }

    private fun sendLogEmail(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "message/rfc822"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(AppConstants.SUPPORT_EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, "배달 자동화 오류 문의")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setPackage("com.google.android.gm")
            }
            startActivity(intent)
        } catch (_: Exception) {
            // Gmail 미설치 시 이메일 앱 선택창으로 폴백
            try {
                val fallback = Intent(Intent.ACTION_SEND).apply {
                    type = "message/rfc822"
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(AppConstants.SUPPORT_EMAIL))
                    putExtra(Intent.EXTRA_SUBJECT, "배달 자동화 오류 문의")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(fallback, "이메일 앱 선택"))
            } catch (e2: Exception) {
                NotificationLogWriter.appendDebugEvent(this, "share_log_failed", "error" to (e2.message ?: "unknown"))
                Toast.makeText(this, "이메일 앱을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
        }
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

        if (!batteryOK()) {
            NotificationLogWriter.appendDebugEvent(
                this,
                "permission_check_failed",
                "permission" to "battery_optimization",
                "action" to "open_settings"
            )
            if (!batteryOpened) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                    batteryOpened = true
                } catch (e: Exception) {
                    NotificationLogWriter.appendDebugEvent(
                        this,
                        "settings_open_failed",
                        "target" to "battery_optimization_auto",
                        "error" to (e.message ?: "unknown")
                    )
                    batteryOpened = true
                }
            }
            return
        }

        NotificationLogWriter.appendDebugEvent(
            this,
            "permission_check_ok",
            "accessibility" to access(),
            "notification_listener" to notifyOK(),
            "battery_optimization" to batteryOK()
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
            append("내비 전환: ${if (AppPrefs.isNaviEnabled(this@MainActivity)) "켜짐" else "꺼짐"} ($n)\n")
            append("주문 자동열기: ${if (AppPrefs.isOrderAutoOpenEnabled(this@MainActivity)) "켜짐" else "꺼짐"}\n")
            append("음악 제어: ${if (AppPrefs.isMusicEnabled(this@MainActivity)) "켜짐" else "꺼짐"}\n")
            append("접근성 권한: ${if (access()) "허용됨" else "필요"}\n")
            append("알림 접근 권한: ${if (notifyOK()) "허용됨" else "필요"}\n")
            append("배터리 최적화: ${if (batteryOK()) "제외됨" else "필요"}")
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

    private fun batteryOK(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }
}
