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

        if (!AppPrefs.isNaviEnabled(this)) {
            NotificationLogWriter.appendNavigationIntent(this, i, selected, result = "navi_off")
            finish()
            return
        }

        val u = i?.data ?: run {
            NotificationLogWriter.appendNavigationIntent(this, i, selected, result = "no_data")
            finish()
            return
        }

        if (selected == "KAKAONAVI" && (u.scheme == "kakaonavi" || u.scheme == "kakaonavi-sdk")) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, u).apply {
                    setPackage("com.locnall.KimGiSa")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                NotificationLogWriter.appendNavigationIntent(
                    this,
                    Intent(Intent.ACTION_VIEW, u).apply { setPackage("com.locnall.KimGiSa") },
                    selected,
                    result = "kakaonavi_passthrough_ok"
                )
            } catch (e: Exception) {
                NotificationLogWriter.appendNavigationIntent(
                    this,
                    null,
                    selected,
                    result = "kakaonavi_passthrough_fail:${e.javaClass.simpleName}:${e.message}"
                )
            }
            finish()
            return
        }

        val coords = dest(u)
        val query = destQuery(u).takeIf { !it.isNullOrBlank() }
            ?: AppPrefs.lastDeliveryDestinationText(this).takeIf { it.isNotBlank() }

        if (coords == null && query == null) {
            NotificationLogWriter.appendNavigationIntent(this, i, selected, result = "dest_parse_failed")
            Toast.makeText(this, "Destination could not be read.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (coords != null) {
            NotificationLogWriter.appendNavigationIntent(this, i, selected, coords.first, coords.second, "dest_coords_ok")
            open(coords.first, coords.second, query)
        } else {
            NotificationLogWriter.appendNavigationIntent(this, i, selected, result = "query_fallback:$query")
            open(null, null, query)
        }

        finish()
    }

    private fun dest(u: Uri): Pair<String, String>? {
        u.getQueryParameter("ep")?.split(",")?.takeIf { it.size == 2 }?.let {
            return Pair(it[0].trim(), it[1].trim())
        }

        val lat = firstParam(u, "goaly", "dlat", "y", "lat", "goalY", "endY", "gy")
        val lon = firstParam(u, "goalx", "dlng", "x", "lng", "lon", "goalX", "endX", "gx")
        return if (lat?.toDoubleOrNull() != null && lon?.toDoubleOrNull() != null) Pair(lat, lon) else null
    }

    private fun destQuery(u: Uri): String? {
        return firstParam(u, "goalname", "dname", "name", "query", "q", "dest", "destination")
    }

    private fun firstParam(u: Uri, vararg keys: String): String? {
        for (key in keys) {
            val value = u.getQueryParameter(key)
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun open(lat: String?, lon: String?, query: String? = null) {
        val defaultName = Uri.encode("delivery")
        val searchName = Uri.encode(query ?: "delivery")

        val target = when (AppPrefs.selectedNavi(this)) {
            "KAKAONAVI" -> if (lat != null && lon != null) {
                Uri.parse("kakaonavi://navigate?name=$defaultName&x=$lon&y=$lat&coord_type=wgs84")
            } else {
                throw IllegalStateException("KakaoNavi requires destination coordinates")
            }

            "KAKAOMAP" -> if (lat != null && lon != null) {
                Uri.parse("kakaomap://route?ep=$lat,$lon&by=car")
            } else {
                Uri.parse("kakaomap://search?q=$searchName&viewType=MAP_CENTER&referrer=$packageName")
            }

            "NAVER" -> if (lat != null && lon != null) {
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

        val targetPackage = when (AppPrefs.selectedNavi(this)) {
            "KAKAONAVI" -> "com.locnall.KimGiSa"
            "KAKAOMAP" -> "net.daum.android.map"
            "NAVER" -> "com.nhn.android.nmap"
            else -> "com.skt.tmap.ku"
        }

        try {
            startActivity(Intent(Intent.ACTION_VIEW, target).apply {
                setPackage(targetPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            NotificationLogWriter.appendNavigationIntent(
                this,
                Intent(Intent.ACTION_VIEW, target).apply { setPackage(targetPackage) },
                AppPrefs.selectedNavi(this),
                lat,
                lon,
                "selected_nav_launch_ok"
            )
        } catch (_: ActivityNotFoundException) {
            NotificationLogWriter.appendNavigationIntent(this, null, AppPrefs.selectedNavi(this), lat, lon, "selected_nav_missing")
            Toast.makeText(this, "Selected navigation app is not installed.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            NotificationLogWriter.appendNavigationIntent(this, null, AppPrefs.selectedNavi(this), lat, lon, "selected_nav_fail:${e.javaClass.simpleName}:${e.message}")
            Toast.makeText(this, "Navigation launch failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
