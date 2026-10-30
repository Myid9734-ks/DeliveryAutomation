package kr.deliveryauto.app

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.media.MediaPlayer
import android.media.RingtoneManager
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

    // packageName → MediaController (SUPPORTED_MUSIC_APPS 전체 관리)
    private val controllers = mutableMapOf<String, MediaController>()
    private val lastAutoOpenAt = mutableMapOf<String, Long>()
    // 패키지 단위 중복 방지 — 같은 앱에서 다른 키로 연속 알림 시(배민 FCM 중복 발송 등) 차단
    private val lastAutoOpenPkgAt = mutableMapOf<String, Long>()
    // [제거 2026-10-30] lastPkgNotificationAt — 배민 알림음 중복 억제용이었으나,
    // 배민 자체 채널 소리와 우리 소리가 항상 2번 울리는 근본 원인을 해결(배민 알림음 재생 중단)하여 불필요

    // 컨트롤러 부착 재시도 — 음악 앱 세션이 늦게 열리는 경우를 대비
    private val attachRetryHandler = Handler(Looper.getMainLooper())
    private var attachRetryScheduled = false
    private var attachRetryCount = 0

    companion object {
        private const val ATTACH_RETRY_MAX = 6
        private const val ATTACH_RETRY_DELAY_MS = 350L
    }

    /** 앱별 콜백 — 재생 재개 완료 감지 (autoPaused 플래그 초기화) */
    private fun makeCallback(packageName: String) = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            NotificationLogWriter.appendAutoOpenResult(
                this@MusicNotificationListener,
                packageName,
                "playback_state_changed",
                "state=${state?.state ?: -1}, autoPaused=${AppPrefs.isAutoPaused(this@MusicNotificationListener)}, targetActive=${AppPrefs.isTargetActive(this@MusicNotificationListener)}, resumePending=${AppPrefs.isResumePending(this@MusicNotificationListener)}"
            )
            if (state?.state != PlaybackState.STATE_PLAYING) return
            if (!AppPrefs.isAutoPaused(this@MusicNotificationListener)) return
            // 멈춘 앱과 재생 시작한 앱이 다르면 무시
            if (AppPrefs.activeMusicPackage(this@MusicNotificationListener) != packageName) return
            // 배달앱이 포그라운드 상태면 방금 pause 요청이 간 것 — resume complete 처리하지 않음
            // (resume + immediate pause 레이스로 autoPaused가 잘못 초기화되는 버그 방지)
            if (AppPrefs.isTargetActive(this@MusicNotificationListener)) return

            val resumeAt = AppPrefs.resumeRequestedAt(this@MusicNotificationListener)
            val elapsed = SystemClock.elapsedRealtime() - if (resumeAt > 0L) resumeAt else AppPrefs.autoPauseAt(this@MusicNotificationListener)
            NotificationLogWriter.appendAutoOpenResult(
                this@MusicNotificationListener,
                packageName,
                "auto_resume_complete",
                "playing_after=${elapsed}ms"
            )
            AppPrefs.setAutoPaused(this@MusicNotificationListener, false)
            AppPrefs.setAutoPauseAt(this@MusicNotificationListener, 0L)
            AppPrefs.setResumePending(this@MusicNotificationListener, false)
            AppPrefs.setResumeRequestedAt(this@MusicNotificationListener, 0L)
            AppPrefs.clearActiveMusicPackage(this@MusicNotificationListener)
        }
    }

    // packageName → 등록된 Callback 인스턴스 (unregister 시 동일 객체 필요)
    private val registeredCallbacks = mutableMapOf<String, MediaController.Callback>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        attachMusicControllers()
    }

    override fun onListenerDisconnected() {
        detachAllControllers()
        attachRetryHandler.removeCallbacksAndMessages(null)
        attachRetryScheduled = false
        attachRetryCount = 0
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // 체험판 만료 & 미구매 시 자동화 중단
        if (!AppPrefs.isFeatureUnlocked(this)) return

        if (sbn.packageName in AppConstants.DELIVERY_PACKAGES) {
            NotificationLogWriter.append(this, sbn)
            val n = sbn.notification
            NotificationLogWriter.appendDebugEvent(
                this,
                "delivery_notification_posted",
                "package" to sbn.packageName,
                "key" to sbn.key,
                "channelId" to if (Build.VERSION.SDK_INT >= 26) n.channelId else null,
                "autoOpenEnabled" to AppPrefs.isOrderAutoOpenEnabled(this),
                // 진단용 — fullScreenIntent/contentIntent/sound/actions 존재 여부 확인
                "hasFullScreenIntent" to (n.fullScreenIntent != null),
                "hasContentIntent" to (n.contentIntent != null),
                "hasSound" to (n.sound != null),
                "soundUri" to n.sound?.toString(),
                "actionCount" to (n.actions?.size ?: 0),
                "actionLabels" to (n.actions?.joinToString(",") { it.title.toString() }),
                "flags" to n.flags,
                "defaults" to n.defaults
            )

            if (AppPrefs.isOrderAutoOpenEnabled(this) && isNewOrderNotification(sbn)) {
                // [이전] 배민 150ms 임계값으로 BrosRiders→FCM 연속 도착 시 우리 알림음 스킵
                // [문제 2026-10-30] 배민 BROS_DELIVERY_ALLOCATION_NOTI 채널 자체가 알림음을 가지고 있어서,
                //   FCM이 단독 도착해도 "배민 채널 소리 + 우리 소리" = 2번 울림.
                //   BrosRiders가 먼저 오는 케이스(sinceLastMs=18)만 잡고, FCM 단독 케이스는 못 잡음.
                // [변경] 배민은 자체 채널 알림음이 있으므로 우리 앱에서는 알림음을 재생하지 않음.
                //   쿠팡 등 다른 배달앱은 자체 알림음이 없으므로 기존대로 재생.
                if (sbn.packageName == AppConstants.PKG_BAEMIN) {
                    NotificationLogWriter.appendDebugEvent(
                        this, "order_alert_sound_skipped",
                        "package" to sbn.packageName,
                        "reason" to "baemin_channel_has_own_sound"
                    )
                } else if (sbn.packageName == AppConstants.PKG_COUPANG_EATS) {
                    playOrderAlertSound()
                    NotificationLogWriter.appendDebugEvent(
                        this, "order_alert_sound_played",
                        "package" to sbn.packageName,
                        "reason" to "coupang_new_order"
                    )
                }

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

            // [제거 2026-10-30] lastPkgNotificationAt 갱신 제거 — 배민 알림음 재생 자체를 중단하여 불필요
        }

        if (sbn.packageName in AppConstants.SUPPORTED_MUSIC_APPS) attachMusicControllers()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName in AppConstants.SUPPORTED_MUSIC_APPS) attachMusicControllers()
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
        val previous = lastAutoOpenAt[sbn.key] ?: 0L
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
        lastAutoOpenAt[sbn.key] = now

        // 패키지 단위 중복 방지 — 동일 앱의 다른 키 알림이 짧은 간격으로 연속 도착할 때 차단
        val previousPkg = lastAutoOpenPkgAt[sbn.packageName] ?: 0L
        if (now - previousPkg < AppConstants.DELIVERY_AUTO_OPEN_DEDUPE_MS) {
            NotificationLogWriter.appendDebugEvent(
                this,
                "delivery_app_open_skipped",
                "package" to sbn.packageName,
                "reason" to "duplicate_suppression_pkg",
                "elapsedMs" to (now - previousPkg)
            )
            return
        }
        lastAutoOpenPkgAt[sbn.packageName] = now

        val contentIntent = sbn.notification.contentIntent
        val launchIntent = packageManager.getLaunchIntentForPackage(sbn.packageName)

        NotificationLogWriter.appendDebugEvent(
            this,
            "delivery_app_open_attempt",
            "package" to sbn.packageName,
            "hasContentIntent" to (contentIntent != null),
            "hasLaunchIntent" to (launchIntent != null)
        )

        // [변경] 기존: 모든 앱 공통으로 launchIntent(600ms 후) → contentIntent(+1500ms 후) 2단계
        // → 문제1: 배민 contentIntent는 BroadcastReceiver 딥링크인데, launchIntent가 먼저 실행되면
        //   배민이 이미 포그라운드 상태로 전환되어 contentIntent의 딥링크가 무시됨 (수락/거절 화면 미이동)
        // → 문제2: 쿠팡 Flutter앱에 FLAG_ACTIVITY_REORDER_TO_FRONT 사용 시 SplashActivity가
        //   재생성되어 콜드스타트처럼 보이는 현상 발생
        // → 변경: 앱별 분기 처리
        //   - 배민: contentIntent만 단독 실행 (자체적으로 앱 포그라운드 전환 + 수락/거절 화면 딥링크)
        //   - 쿠팡: contentIntent 우선, 없으면 launchIntent에 FLAG_ACTIVITY_SINGLE_TOP 사용

        Handler(Looper.getMainLooper()).postDelayed({
            when (sbn.packageName) {
                AppConstants.PKG_BAEMIN -> openBaemin(sbn, contentIntent, launchIntent)
                AppConstants.PKG_COUPANG_EATS -> openCoupang(sbn, contentIntent, launchIntent)
                else -> openGeneric(sbn, contentIntent, launchIntent)
            }
        }, AppConstants.LAUNCH_INTENT_DELAY_MS)
    }

    /** 배민 전용 — fullScreenIntent 우선, 없으면 contentIntent 단독 실행 */
    private fun openBaemin(sbn: StatusBarNotification, contentIntent: PendingIntent?, launchIntent: Intent?) {
        // [이전 2026-10-23] launchIntent(SINGLE_TOP) → contentIntent 2단계
        // [문제 2026-10-30] launchIntent가 배민 메인 Activity를 foreground로 올리면,
        //   배민이 자체적으로 띄우려는 수락/거절 fullScreenIntent 팝업이 suppress됨.
        //   contentIntent.send()는 알림 상세 화면(systemui)을 열 뿐 수락/거절 딥링크가 아님.
        //   로그: launchIntent 성공 → 배민커넥트 foreground → contentIntent 성공 → 알림 세부정보(systemui)
        //         → 수락/거절 화면 미노출
        // [변경] fullScreenIntent를 우선 사용 — 배민은 신규배달 알림에 fullScreenIntent로
        //   수락/거절 팝업 Activity를 등록하며, 이것이 실제 배달 수락 화면.
        //   launchIntent는 사용하지 않음 (메인 Activity가 올라오면 fullScreen 팝업과 충돌).
        val fullScreenIntent = sbn.notification.fullScreenIntent

        NotificationLogWriter.appendDebugEvent(
            this, "baemin_intent_analysis",
            "package" to sbn.packageName,
            "hasFullScreenIntent" to (fullScreenIntent != null),
            "hasContentIntent" to (contentIntent != null),
            "hasLaunchIntent" to (launchIntent != null)
        )

        if (fullScreenIntent != null) {
            // fullScreenIntent — 배민 수락/거절 팝업 Activity 직접 실행
            try {
                fullScreenIntent.send()
                AppPrefs.setLastAutoOpenSentAt(this, sbn.packageName, SystemClock.elapsedRealtime())
                NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "fullScreenIntent", "성공")
                NotificationLogWriter.appendDebugEvent(
                    this, "delivery_app_switch_result",
                    "package" to sbn.packageName,
                    "method" to "fullScreenIntent",
                    "result" to "success"
                )
            } catch (e: Exception) {
                NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "fullScreenIntent", "실패: ${e.javaClass.simpleName}: ${e.message}")
                // fullScreenIntent 실패 시 contentIntent 시도
                trySendContentIntent(sbn, contentIntent, launchIntent)
            }
        } else {
            // fullScreenIntent 없음 — contentIntent 단독 시도, 실패 시 launchIntent fallback
            trySendContentIntent(sbn, contentIntent, launchIntent)
        }
    }

    /** 배민 fallback — contentIntent 단독 시도, 없으면 launchIntent */
    private fun trySendContentIntent(sbn: StatusBarNotification, contentIntent: PendingIntent?, launchIntent: Intent?) {
        if (contentIntent != null) {
            try {
                NotificationLogWriter.appendDebugEvent(
                    this, "content_intent_info",
                    "package" to sbn.packageName,
                    "creatorPackage" to contentIntent.creatorPackage,
                    "creatorUid" to contentIntent.creatorUid
                )
                contentIntent.send()
                AppPrefs.setLastAutoOpenSentAt(this, sbn.packageName, SystemClock.elapsedRealtime())
                NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "contentIntent", "성공")
            } catch (e: Exception) {
                NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "contentIntent", "실패: ${e.javaClass.simpleName}: ${e.message}")
                // contentIntent도 실패 시 launchIntent fallback
                if (launchIntent != null) {
                    try {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        startActivity(launchIntent)
                        AppPrefs.setLastAutoOpenSentAt(this, sbn.packageName, SystemClock.elapsedRealtime())
                        NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "launchIntent", "성공(fallback)")
                    } catch (e2: Exception) {
                        NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "launchIntent", "실패: ${e2.javaClass.simpleName}: ${e2.message}")
                    }
                }
            }
        } else if (launchIntent != null) {
            try {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(launchIntent)
                AppPrefs.setLastAutoOpenSentAt(this, sbn.packageName, SystemClock.elapsedRealtime())
                NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "launchIntent", "성공(no_content_intent)")
            } catch (e: Exception) {
                NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "launchIntent", "실패: ${e.javaClass.simpleName}: ${e.message}")
            }
        } else {
            NotificationLogWriter.appendDebugEvent(
                this, "delivery_app_open_skipped",
                "package" to sbn.packageName,
                "reason" to "no_intent_available"
            )
        }
    }

    /** 쿠팡 전용 — launchIntent(SINGLE_TOP)로 포그라운드 전환 후 contentIntent로 주문 화면 이동 */
    private fun openCoupang(sbn: StatusBarNotification, contentIntent: PendingIntent?, launchIntent: Intent?) {
        // 1단계: launchIntent로 포그라운드 전환 (SINGLE_TOP으로 콜드스타트 방지)
        if (launchIntent != null) {
            try {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(launchIntent)
                AppPrefs.setLastAutoOpenSentAt(this, sbn.packageName, SystemClock.elapsedRealtime())
                NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "launchIntent", "성공(SINGLE_TOP)")
                NotificationLogWriter.appendDebugEvent(
                    this, "delivery_app_switch_result",
                    "package" to sbn.packageName,
                    "method" to "launchIntent_single_top",
                    "result" to "success"
                )
            } catch (e: Exception) {
                NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "launchIntent", "실패: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // 2단계: contentIntent로 주문 수락/거절 화면 딥링크
        if (contentIntent != null) {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    NotificationLogWriter.appendDebugEvent(
                        this, "content_intent_info",
                        "package" to sbn.packageName,
                        "creatorPackage" to contentIntent.creatorPackage,
                        "creatorUid" to contentIntent.creatorUid
                    )
                    contentIntent.send()
                    AppPrefs.setLastAutoOpenSentAt(this, sbn.packageName, SystemClock.elapsedRealtime())
                    NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "contentIntent", "성공")
                } catch (e: Exception) {
                    NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "contentIntent", "실패: ${e.javaClass.simpleName}: ${e.message}")
                }
            }, AppConstants.CONTENT_INTENT_DELAY_MS)
        } else if (launchIntent == null) {
            NotificationLogWriter.appendDebugEvent(
                this, "delivery_app_open_skipped",
                "package" to sbn.packageName,
                "reason" to "no_intent_available"
            )
        }
    }

    /** 범용 폴백 — launchIntent로 포그라운드 전환 */
    private fun launchWithFallback(sbn: StatusBarNotification, launchIntent: Intent?) {
        if (launchIntent == null) {
            NotificationLogWriter.appendDebugEvent(
                this, "delivery_app_open_skipped",
                "package" to sbn.packageName,
                "reason" to "no_intent_available"
            )
            return
        }
        try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(launchIntent)
            AppPrefs.setLastAutoOpenSentAt(this, sbn.packageName, SystemClock.elapsedRealtime())
            NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "launchIntent", "성공")
            NotificationLogWriter.appendDebugEvent(
                this, "delivery_app_switch_result",
                "package" to sbn.packageName,
                "method" to "launchIntent_fallback",
                "result" to "success"
            )
        } catch (e: Exception) {
            NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "launchIntent", "실패: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 기타 배달앱 — 기존 2단계 방식 유지 */
    private fun openGeneric(sbn: StatusBarNotification, contentIntent: PendingIntent?, launchIntent: Intent?) {
        if (launchIntent != null) {
            try {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                startActivity(launchIntent)
                AppPrefs.setLastAutoOpenSentAt(this, sbn.packageName, SystemClock.elapsedRealtime())
                NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "launchIntent", "성공")
            } catch (e: Exception) {
                NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "launchIntent", "실패: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        if (contentIntent != null) {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    contentIntent.send()
                    AppPrefs.setLastAutoOpenSentAt(this, sbn.packageName, SystemClock.elapsedRealtime())
                    NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "contentIntent", "성공")
                } catch (e: Exception) {
                    NotificationLogWriter.appendAutoOpenResult(this, sbn.packageName, "contentIntent", "실패: ${e.javaClass.simpleName}: ${e.message}")
                }
            }, AppConstants.CONTENT_INTENT_DELAY_MS)
        } else if (launchIntent == null) {
            NotificationLogWriter.appendDebugEvent(
                this, "delivery_app_open_skipped",
                "package" to sbn.packageName,
                "reason" to "no_intent_available"
            )
        }
    }

    private fun playOrderAlertSound() {
        try {
            MediaPlayer.create(this, R.raw.baemin_new_order)?.apply {
                setOnCompletionListener { release() }
                start()
            }
        } catch (_: Exception) {
            try {
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                RingtoneManager.getRingtone(this, soundUri)?.play()
            } catch (_: Exception) { }
        }
    }

    /** SUPPORTED_MUSIC_APPS 전체의 MediaController를 최신 세션으로 갱신 */
    private fun attachMusicControllers() {
        try {
            val manager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, MusicNotificationListener::class.java)
            val activeSessions = manager.getActiveSessions(component)
                .filter { it.packageName in AppConstants.SUPPORTED_MUSIC_APPS }
                .associateBy { it.packageName }

            // 새로 연결되거나 세션 토큰이 바뀐 앱만 갱신
            for (pkg in AppConstants.SUPPORTED_MUSIC_APPS) {
                val newCtrl = activeSessions[pkg]
                val oldCtrl = controllers[pkg]

                if (oldCtrl?.sessionToken == newCtrl?.sessionToken) continue

                // 기존 콜백 해제
                oldCtrl?.let { registeredCallbacks[pkg]?.let(it::unregisterCallback) }

                if (newCtrl != null) {
                    val cb = makeCallback(pkg)
                    newCtrl.registerCallback(cb)
                    controllers[pkg] = newCtrl
                    registeredCallbacks[pkg] = cb
                } else {
                    controllers.remove(pkg)
                    registeredCallbacks.remove(pkg)
                }

                NotificationLogWriter.appendDebugEvent(
                    this, "music_controller_state",
                    "package" to pkg,
                    "result" to if (newCtrl != null) "attached" else "none",
                    "retryCount" to attachRetryCount
                )
            }

            // autoPaused 앱의 컨트롤러가 붙었으면 재개 시도
            val targetPkg = AppPrefs.activeMusicPackage(this).ifBlank { MusicSessionHelper.YOUTUBE_MUSIC }
            if (controllers.containsKey(targetPkg)) {
                attachRetryHandler.removeCallbacksAndMessages(null)
                attachRetryScheduled = false
                attachRetryCount = 0
                if (AppPrefs.isAutoPaused(this) &&
                    (AppPrefs.isResumePending(this) || !AppPrefs.isTargetActive(this))) {
                    NotificationLogWriter.appendDebugEvent(
                        this, "music_controller_resume_trigger",
                        "package" to targetPkg,
                        "reason" to "controller_attached",
                        "retryCount" to AppPrefs.resumeRetryCount(this)
                    )
                    MusicSessionHelper.resumeIfAutoPaused(this)
                }
            } else if (shouldRetryControllerAttach()) {
                scheduleControllerAttachRetry("attach_none")
            }
        } catch (_: SecurityException) {
            detachAllControllers()
            NotificationLogWriter.appendDebugEvent(
                this, "music_controller_state", "result" to "security_exception"
            )
            if (shouldRetryControllerAttach()) scheduleControllerAttachRetry("security_exception")
        }
    }

    private fun detachAllControllers() {
        for ((pkg, ctrl) in controllers) {
            registeredCallbacks[pkg]?.let(ctrl::unregisterCallback)
        }
        controllers.clear()
        registeredCallbacks.clear()
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
            this, "music_controller_retry_scheduled",
            "reason" to reason,
            "retryCount" to attachRetryCount,
            "maxRetry" to ATTACH_RETRY_MAX
        )
        attachRetryHandler.postDelayed({
            attachRetryScheduled = false
            attachMusicControllers()
            val targetPkg = AppPrefs.activeMusicPackage(this).ifBlank { MusicSessionHelper.YOUTUBE_MUSIC }
            if (!controllers.containsKey(targetPkg)) {
                scheduleControllerAttachRetry("retry_still_none")
            } else {
                attachRetryCount = 0
            }
        }, ATTACH_RETRY_DELAY_MS)
    }
}
