package com.example.musicdeliveryswitch
import android.content.Context
object AppPrefs {
 private const val FILE="delivery_automation_prefs"
 private fun p(c:Context)=c.getSharedPreferences(FILE,Context.MODE_PRIVATE)
 fun isMusicEnabled(c:Context)=p(c).getBoolean("music_enabled",true); fun setMusicEnabled(c:Context,v:Boolean)=p(c).edit().putBoolean("music_enabled",v).apply()
 fun isNaviEnabled(c:Context)=p(c).getBoolean("navi_enabled",true); fun setNaviEnabled(c:Context,v:Boolean)=p(c).edit().putBoolean("navi_enabled",v).apply()
 fun isOrderAutoOpenEnabled(c:Context)=p(c).getBoolean("order_auto_open_enabled",true); fun setOrderAutoOpenEnabled(c:Context,v:Boolean)=p(c).edit().putBoolean("order_auto_open_enabled",v).apply()
 fun selectedNavi(c:Context)=p(c).getString("selected_navi","TMAP")?:"TMAP"; fun setSelectedNavi(c:Context,v:String)=p(c).edit().putString("selected_navi",v).apply()
 fun isAutoPaused(c:Context)=p(c).getBoolean("auto_paused",false); fun setAutoPaused(c:Context,v:Boolean)=p(c).edit().putBoolean("auto_paused",v).apply()
 fun isResumePending(c:Context)=p(c).getBoolean("resume_pending",false); fun setResumePending(c:Context,v:Boolean)=p(c).edit().putBoolean("resume_pending",v).apply()
 fun resumeRequestedAt(c:Context)=p(c).getLong("resume_requested_at",0L); fun setResumeRequestedAt(c:Context,v:Long)=p(c).edit().putLong("resume_requested_at",v).apply()
 fun isTargetActive(c:Context)=p(c).getBoolean("target_active",false); fun setTargetActive(c:Context,v:Boolean)=p(c).edit().putBoolean("target_active",v).apply()
 fun isNavSessionActive(c:Context)=p(c).getBoolean("nav_session_active",false); fun setNavSessionActive(c:Context,v:Boolean)=p(c).edit().putBoolean("nav_session_active",v).apply()
 fun autoPauseAt(c:Context)=p(c).getLong("auto_pause_at",0L); fun setAutoPauseAt(c:Context,v:Long)=p(c).edit().putLong("auto_pause_at",v).apply()
 fun lastForegroundPackage(c:Context)=p(c).getString("last_foreground_package","")?:""; fun setLastForegroundPackage(c:Context,v:String)=p(c).edit().putString("last_foreground_package",v).apply()
 fun lastDeliveryDestinationText(c:Context)=p(c).getString("last_delivery_destination_text","")?:""; fun setLastDeliveryDestinationText(c:Context,v:String)=p(c).edit().putString("last_delivery_destination_text",v).apply()
 fun lastDeliveryDestinationAt(c:Context)=p(c).getLong("last_delivery_destination_at",0L); fun setLastDeliveryDestinationAt(c:Context,v:Long)=p(c).edit().putLong("last_delivery_destination_at",v).apply()
}
