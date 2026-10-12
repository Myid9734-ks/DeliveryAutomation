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

# R8 full mode 호환
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
