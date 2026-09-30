package com.example.musicdeliveryswitch

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.service.notification.StatusBarNotification
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NotificationLogWriter {
    private const val LOG_ENABLED = true
    private const val FILE_NAME = "배달자동화_알림로그.txt"

    fun isDeliveryPackage(packageName: String?): Boolean = packageName in AppConstants.DELIVERY_PACKAGES

    @Synchronized
    fun appendDebugEvent(context: Context, event: String, vararg fields: Pair<String, Any?>) {
        val log = buildString {
            appendLine("============================================================")
            appendLine("유형=디버그이벤트")
            appendLine("수신시각=${now()}")
            appendLine("event=$event")
            fields.forEach { (key, value) ->
                appendLine("$key=${value?.toString().orEmpty()}")
            }
        }
        safeWrite(context, log)
    }

    @Synchronized
    fun append(context: Context, sbn: StatusBarNotification) {
        if (!isDeliveryPackage(sbn.packageName)) return

        val n = sbn.notification
        val e = n.extras
        val timestamp = now()
        val appName = AppConstants.deliveryAppName(sbn.packageName)

        val lines = e?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString(" | ") { it.toString() }
            .orEmpty()

        val log = buildString {
            appendLine("============================================================")
            appendLine("유형=알림")
            appendLine("수신시각=$timestamp")
            appendLine("앱=$appName")
            appendLine("package=${sbn.packageName}")
            appendLine("id=${sbn.id}")
            appendLine("tag=${sbn.tag.orEmpty()}")
            appendLine("key=${sbn.key}")
            appendLine("postTime=${sbn.postTime}")
            appendLine("channelId=${if (Build.VERSION.SDK_INT >= 26) n.channelId.orEmpty() else ""}")
            appendLine("category=${n.category.orEmpty()}")
            appendLine("group=${n.group.orEmpty()}")
            appendLine("ticker=${n.tickerText?.toString().orEmpty()}")
            appendLine("title=${e?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()}")
            appendLine("titleBig=${e?.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString().orEmpty()}")
            appendLine("text=${e?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()}")
            appendLine("bigText=${e?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()}")
            appendLine("subText=${e?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()}")
            appendLine("infoText=${e?.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString().orEmpty()}")
            appendLine("summaryText=${e?.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString().orEmpty()}")
            appendLine("textLines=$lines")
            appendLine("flags=${n.flags}")
        }
        safeWrite(context, log)
    }

    @Synchronized
    fun appendNavigationIntent(context: Context, intent: Intent?, selectedNavi: String, parsedLat: String? = null, parsedLon: String? = null, result: String = "수신") {
        val data: Uri? = intent?.data
        val extrasText = bundleToText(intent?.extras)
        val log = buildString {
            appendLine("============================================================")
            appendLine("유형=네비Intent")
            appendLine("수신시각=${now()}")
            appendLine("result=$result")
            appendLine("selectedNavi=$selectedNavi")
            appendLine("action=${intent?.action.orEmpty()}")
            appendLine("data=${data?.toString().orEmpty()}")
            appendLine("scheme=${data?.scheme.orEmpty()}")
            appendLine("host=${data?.host.orEmpty()}")
            appendLine("path=${data?.path.orEmpty()}")
            appendLine("query=${data?.query.orEmpty()}")
            appendLine("categories=${intent?.categories?.joinToString(",").orEmpty()}")
            appendLine("flags=${intent?.flags ?: 0}")
            appendLine("component=${intent?.component?.flattenToShortString().orEmpty()}")
            appendLine("package=${intent?.`package`.orEmpty()}")
            appendLine("extras=$extrasText")
            appendLine("parsedLat=${parsedLat.orEmpty()}")
            appendLine("parsedLon=${parsedLon.orEmpty()}")
        }
        safeWrite(context, log)
    }

    @Synchronized
    fun appendNavigationTransition(context: Context, fromPackage: String?, toPackage: String?, eventType: Int, eventText: String) {
        val log = buildString {
            appendLine("============================================================")
            appendLine("유형=네비화면전환")
            appendLine("수신시각=${now()}")
            appendLine("fromPackage=${fromPackage.orEmpty()}")
            appendLine("toPackage=${toPackage.orEmpty()}")
            appendLine("eventType=$eventType")
            appendLine("eventText=$eventText")
            appendLine("selectedNavi=${AppPrefs.selectedNavi(context)}")
            appendLine("설명=네비 Intent가 우리 앱을 거치지 않고 직접 실행되는 경우를 확인하기 위한 로그")
        }
        safeWrite(context, log)
    }

    @Synchronized
    fun appendAutoOpenResult(context: Context, packageName: String, method: String, result: String) {
        val appName = AppConstants.deliveryAppName(packageName)
        val log = buildString {
            appendLine("============================================================")
            appendLine("유형=신규주문_자동열기")
            appendLine("수신시각=${now()}")
            appendLine("앱=$appName")
            appendLine("package=$packageName")
            appendLine("method=$method")
            appendLine("result=$result")
        }
        safeWrite(context, log)
    }

    fun getLogUri(context: Context): Uri? {
        val file = logFile(context)
        if (!file.exists()) return null
        return androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun bundleToText(bundle: Bundle?): String {
        if (bundle == null || bundle.isEmpty) return ""
        return try {
            bundle.keySet().sorted().joinToString(" | ") { key ->
                val value = try { bundle.get(key) } catch (_: Exception) { "<읽기실패>" }
                "$key=$value"
            }
        } catch (_: Exception) {
            "<extras 읽기실패>"
        }
    }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.KOREA).format(Date())

    private fun logFile(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        return File(dir, FILE_NAME)
    }

    private const val MAX_LOG_BYTES = 500 * 1024L // 500KB

    private fun safeWrite(context: Context, text: String) {
        if (!LOG_ENABLED) return
        try {
            val file = logFile(context)
            file.parentFile?.mkdirs()
            if (file.exists() && file.length() > MAX_LOG_BYTES) trimLog(file)
            file.appendText(text, Charsets.UTF_8)
        } catch (_: Exception) {
            // 로깅 실패가 기존 자동화 기능에 영향을 주지 않도록 무시한다.
        }
    }

    private fun trimLog(file: File) {
        val content = file.readText(Charsets.UTF_8)
        file.writeText(content.substring(content.length / 2), Charsets.UTF_8)
    }
}
