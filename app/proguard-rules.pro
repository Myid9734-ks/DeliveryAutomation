# ──────────────────────────────────────────────────
# 배달 자동화 - ProGuard/R8 Rules
# ──────────────────────────────────────────────────

# AccessibilityService — 시스템이 리플렉션으로 생성
-keep class kr.deliveryauto.app.ForegroundAppAccessibilityService { *; }

# NotificationListenerService — 시스템이 리플렉션으로 생성
-keep class kr.deliveryauto.app.MusicNotificationListener { *; }

# BroadcastReceiver — 매니페스트에서 직접 참조
-keep class kr.deliveryauto.app.BootReceiver { *; }

# FileProvider — 매니페스트에서 직접 참조
-keep class androidx.core.content.FileProvider { *; }

# ViewBinding — 리플렉션 기반 inflate
-keep class kr.deliveryauto.app.databinding.** { *; }

# 결제 & 체험판 관리
-keep class kr.deliveryauto.app.BillingManager { *; }
-keep class kr.deliveryauto.app.TrialManager { *; }

# Singleton 유틸리티 — 다른 클래스에서 정적 호출
-keep class kr.deliveryauto.app.AppPrefs { *; }
-keep class kr.deliveryauto.app.AppConstants { *; }
-keep class kr.deliveryauto.app.MusicSessionHelper { *; }
-keep class kr.deliveryauto.app.NotificationLogWriter { *; }

# R8 full mode 호환
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
