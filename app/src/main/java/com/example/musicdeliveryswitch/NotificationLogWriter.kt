package com.example.musicdeliveryswitch

import android.app.Notification
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.service.notification.StatusBarNotification
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NotificationLogWriter {
    private const val FILE_NAME = "배달자동화_알림로그.txt"
    private const val RELATIVE_PATH = "Download/DeliveryAutomation/"

    private val deliveryPackages = setOf(
        "com.woowahan.bros",
        "com.coupang.mobile.eats.courier"
    )

    fun isDeliveryPackage(packageName: String?): Boolean = packageName in deliveryPackages

    @Synchronized
    fun append(context: Context, sbn: StatusBarNotification) {
        if (!isDeliveryPackage(sbn.packageName)) return

        val n = sbn.notification
        val e = n.extras
        val timestamp = now()
        val appName = when (sbn.packageName) {
            "com.woowahan.bros" -> "배민"
            "com.coupang.mobile.eats.courier" -> "쿠팡"
            else -> sbn.packageName
        }

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
        val appName = when (packageName) {
            "com.woowahan.bros" -> "배민"
            "com.coupang.mobile.eats.courier" -> "쿠팡"
            else -> packageName
        }
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

    private fun safeWrite(context: Context, text: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) appendToDownloads(context, text)
            else appendToAppExternal(context, text)
        } catch (_: Exception) {
            // 로깅 실패가 기존 자동화 기능에 영향을 주지 않도록 무시한다.
        }
    }

    private const val LOG_PREFS = "notification_log_writer"
    private const val LOG_URI_KEY = "single_log_uri"

    private fun appendToDownloads(context: Context, text: String) {
        val resolver = context.contentResolver
        val prefs = context.getSharedPreferences(LOG_PREFS, Context.MODE_PRIVATE)

        // 한 번 만든 로그 파일의 Uri를 저장해 이후 모든 로그를 같은 파일에 append한다.
        val savedUri = prefs.getString(LOG_URI_KEY, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (savedUri != null) {
            try {
                resolver.openOutputStream(savedUri, "wa")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                    writer.write(text)
                } ?: throw IllegalStateException("로그 파일 열기 실패")
                return
            } catch (_: Exception) {
                prefs.edit().remove(LOG_URI_KEY).apply()
            }
        }

        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
        val args = arrayOf(FILE_NAME, RELATIVE_PATH)

        // 현재 앱이 접근 가능한 동일 이름 파일이 있으면 가장 최근 파일 하나만 사용한다.
        val existingUri = resolver.query(
            collection, projection, selection, args,
            "${MediaStore.Downloads.DATE_ADDED} DESC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                android.content.ContentUris.withAppendedId(collection, id)
            } else null
        }

        val uri = existingUri ?: resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
            }
        ) ?: return

        prefs.edit().putString(LOG_URI_KEY, uri.toString()).apply()

        resolver.openOutputStream(uri, "wa")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            writer.write(text)
        }
    }

    private fun appendToAppExternal(context: Context, text: String) {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val file = File(dir, FILE_NAME)
        file.parentFile?.mkdirs()
        file.appendText(text, Charsets.UTF_8)
    }
}
