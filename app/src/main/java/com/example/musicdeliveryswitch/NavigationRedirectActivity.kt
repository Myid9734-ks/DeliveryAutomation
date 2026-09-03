package com.example.musicdeliveryswitch

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

class NavigationRedirectActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        handle(intent)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        handle(i)
    }

    private fun handle(i: Intent?) {
        val selected = AppPrefs.selectedNavi(this)
        NotificationLogWriter.appendNavigationIntent(this, i, selected, result = "received")
        NotificationLogWriter.appendDebugEvent(
            this,
            "navigation_request_received",
            "selectedNavi" to selected,
            "action" to i?.action,
            "data" to i?.data?.toString(),
            "package" to i?.`package`,
            "component" to i?.component?.flattenToShortString()
        )

        if (!AppPrefs.isNaviEnabled(this)) {
            NotificationLogWriter.appendNavigationIntent(this, i, selected, result = "navi_off")
            NotificationLogWriter.appendDebugEvent(
                this,
                "navigation_request_blocked",
                "reason" to "navi_disabled",
                "selectedNavi" to selected
            )
            finish()
            return
        }

        val u = i?.data ?: run {
            NotificationLogWriter.appendNavigationIntent(this, i, selected, result = "no_data")
            NotificationLogWriter.appendDebugEvent(
                this,
                "navigation_request_blocked",
                "reason" to "no_data",
                "selectedNavi" to selected
            )
            finish()
            return
        }

        if (selected == AppConstants.NAVI_KAKAONAVI && (u.scheme == "kakaonavi" || u.scheme == "kakaonavi-sdk")) {
            launchKakaoNaviPassthrough(u)
            finish()
            return
        }

        val (coords, query) = resolveDestination(u)

        NotificationLogWriter.appendDebugEvent(
            this,
            "navigation_destination_resolved",
            "hasCoords" to (coords != null),
            "hasQuery" to (!query.isNullOrBlank()),
            "querySource" to if (destQuery(u).isNullOrBlank()) "cache" else "intent"
        )

        if (coords == null && query == null) {
            NotificationLogWriter.appendNavigationIntent(this, i, selected, result = "dest_parse_failed")
            NotificationLogWriter.appendDebugEvent(
                this,
                "navigation_request_blocked",
                "reason" to "destination_parse_failed",
                "selectedNavi" to selected,
                "data" to u.toString()
            )
            Toast.makeText(this, "Destination could not be read.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (coords != null) {
            NotificationLogWriter.appendNavigationIntent(this, i, selected, coords.first, coords.second, "dest_coords_ok")
            NotificationLogWriter.appendDebugEvent(
                this,
                "navigation_route_launch",
                "mode" to "coords",
                "lat" to coords.first,
                "lon" to coords.second,
                "query" to query
            )
            launchNavigation(coords.first, coords.second, query)
        } else {
            NotificationLogWriter.appendNavigationIntent(this, i, selected, result = "query_fallback:$query")
            NotificationLogWriter.appendDebugEvent(
                this,
                "navigation_route_launch",
                "mode" to "query",
                "query" to query
            )
            launchNavigation(null, null, query)
        }

        finish()
    }

    private fun resolveDestination(u: Uri): Pair<Pair<String, String>?, String?> {
        val coords = dest(u)
        val query = destQuery(u).takeIf { !it.isNullOrBlank() }
            ?: AppPrefs.lastDeliveryDestinationText(this).takeIf { it.isNotBlank() }
        return Pair(coords, query)
    }

    private fun launchKakaoNaviPassthrough(u: Uri) {
        val passthroughIntent = Intent(Intent.ACTION_VIEW, u).apply {
            setPackage(AppConstants.PKG_KAKAONAVI)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val selected = AppPrefs.selectedNavi(this)
        NotificationLogWriter.appendDebugEvent(
            this,
            "navigation_passthrough_attempt",
            "scheme" to u.scheme,
            "targetPackage" to AppConstants.PKG_KAKAONAVI
        )
        try {
            startActivity(passthroughIntent)
            NotificationLogWriter.appendNavigationIntent(
                this,
                Intent(Intent.ACTION_VIEW, u).apply { setPackage(AppConstants.PKG_KAKAONAVI) },
                selected,
                result = "kakaonavi_passthrough_ok"
            )
            NotificationLogWriter.appendDebugEvent(
                this,
                "navigation_passthrough_result",
                "result" to "ok",
                "selectedNavi" to selected
            )
        } catch (e: Exception) {
            NotificationLogWriter.appendNavigationIntent(
                this,
                null,
                selected,
                result = "kakaonavi_passthrough_fail:${e.javaClass.simpleName}:${e.message}"
            )
            NotificationLogWriter.appendDebugEvent(
                this,
                "navigation_passthrough_result",
                "result" to "fail",
                "error" to "${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun dest(u: Uri): Pair<String, String>? {
        // kakaonavi-sdk://navigate?param={"destination":{"x":lon,"y":lat},...}
        if (u.scheme == "kakaonavi-sdk") {
            parseKakaoNaviSdkCoords(u)?.let { return it }
        }

        u.getQueryParameter("ep")?.split(",")?.takeIf { it.size == 2 }?.let {
            return Pair(it[0].trim(), it[1].trim())
        }

        val lat = firstParam(u, "goaly", "dlat", "y", "lat", "goalY", "endY", "gy")
        val lon = firstParam(u, "goalx", "dlng", "x", "lng", "lon", "goalX", "endX", "gx")
        return if (lat?.toDoubleOrNull() != null && lon?.toDoubleOrNull() != null) Pair(lat, lon) else null
    }

    private fun parseKakaoNaviSdkCoords(u: Uri): Pair<String, String>? {
        return try {
            val paramStr = u.getQueryParameter("param") ?: return null
            val dest = org.json.JSONObject(paramStr).optJSONObject("destination") ?: return null
            val x = dest.optString("x").takeIf { it.isNotBlank() } ?: return null  // 경도
            val y = dest.optString("y").takeIf { it.isNotBlank() } ?: return null  // 위도
            if (x.toDoubleOrNull() != null && y.toDoubleOrNull() != null) Pair(y, x) else null
        } catch (_: Exception) {
            null
        }
    }

    private fun destQuery(u: Uri): String? {
        if (u.scheme == "kakaonavi-sdk") {
            try {
                val paramStr = u.getQueryParameter("param")
                if (!paramStr.isNullOrBlank()) {
                    val name = org.json.JSONObject(paramStr).optJSONObject("destination")?.optString("name")
                    if (!name.isNullOrBlank()) return name
                }
            } catch (_: Exception) {}
        }
        return firstParam(u, "goalname", "dname", "name", "query", "q", "dest", "destination")
    }

    private fun firstParam(u: Uri, vararg keys: String): String? {
        for (key in keys) {
            val value = u.getQueryParameter(key)
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun launchNavigation(lat: String?, lon: String?, query: String? = null) {
        val defaultName = Uri.encode("delivery")
        val searchName = Uri.encode(query ?: "delivery")
        val selected = AppPrefs.selectedNavi(this)

        val target = when (selected) {
            AppConstants.NAVI_KAKAONAVI -> if (lat != null && lon != null) {
                Uri.parse("kakaonavi://navigate?name=$defaultName&x=$lon&y=$lat&coord_type=wgs84")
            } else {
                Toast.makeText(this, "카카오내비는 좌표가 필요합니다.", Toast.LENGTH_SHORT).show()
                return
            }

            AppConstants.NAVI_KAKAOMAP -> if (lat != null && lon != null) {
                Uri.parse("kakaomap://route?ep=$lat,$lon&by=car")
            } else {
                Uri.parse("kakaomap://search?q=$searchName&viewType=MAP_CENTER&referrer=$packageName")
            }

            AppConstants.NAVI_NAVER -> if (lat != null && lon != null) {
                Uri.parse("nmap://navigation?dlat=$lat&dlng=$lon&dname=$defaultName&appname=$packageName")
            } else {
                Uri.parse("nmap://search?query=$searchName&appname=$packageName")
            }

            else -> if (lat != null && lon != null) {
                Uri.parse("tmap://route?goalname=$defaultName&goalx=$lon&goaly=$lat")
            } else {
                Uri.parse("tmap://search?name=$searchName")
            }
        }

        val targetPackage = when (selected) {
            AppConstants.NAVI_KAKAONAVI -> AppConstants.PKG_KAKAONAVI
            AppConstants.NAVI_KAKAOMAP -> AppConstants.PKG_KAKAOMAP
            AppConstants.NAVI_NAVER -> AppConstants.PKG_NAVERMAP
            else -> AppConstants.PKG_TMAP
        }

        NotificationLogWriter.appendDebugEvent(
            this,
            "navigation_launch_prepared",
            "selectedNavi" to selected,
            "targetPackage" to targetPackage,
            "targetUri" to target.toString(),
            "lat" to lat,
            "lon" to lon,
            "query" to query
        )

        try {
            startActivity(Intent(Intent.ACTION_VIEW, target).apply {
                setPackage(targetPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            NotificationLogWriter.appendNavigationIntent(
                this,
                Intent(Intent.ACTION_VIEW, target).apply { setPackage(targetPackage) },
                selected,
                lat,
                lon,
                "selected_nav_launch_ok"
            )
            NotificationLogWriter.appendDebugEvent(
                this,
                "navigation_launch_result",
                "result" to "ok",
                "selectedNavi" to selected,
                "targetPackage" to targetPackage
            )
        } catch (_: ActivityNotFoundException) {
            NotificationLogWriter.appendNavigationIntent(this, null, selected, lat, lon, "selected_nav_missing")
            NotificationLogWriter.appendDebugEvent(
                this,
                "navigation_launch_result",
                "result" to "missing",
                "selectedNavi" to selected,
                "targetPackage" to targetPackage
            )
            Toast.makeText(this, "Selected navigation app is not installed.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            NotificationLogWriter.appendNavigationIntent(this, null, selected, lat, lon, "selected_nav_fail:${e.javaClass.simpleName}:${e.message}")
            NotificationLogWriter.appendDebugEvent(
                this,
                "navigation_launch_result",
                "result" to "fail",
                "selectedNavi" to selected,
                "targetPackage" to targetPackage,
                "error" to "${e.javaClass.simpleName}: ${e.message}"
            )
            Toast.makeText(this, "Navigation launch failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
