package com.example.musicdeliveryswitch
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.example.musicdeliveryswitch.databinding.ActivityMainBinding
class MainActivity:AppCompatActivity(){
 private lateinit var b:ActivityMainBinding; private var ao=false; private var no=false
 override fun onCreate(x:Bundle?){super.onCreate(x);b=ActivityMainBinding.inflate(layoutInflater);setContentView(b.root)
  b.switchNavi.isChecked=AppPrefs.isNaviEnabled(this);b.switchOrderAutoOpen.isChecked=AppPrefs.isOrderAutoOpenEnabled(this);b.switchMusic.isChecked=AppPrefs.isMusicEnabled(this)
  when(AppPrefs.selectedNavi(this)){"KAKAONAVI"->b.radioKakaoNavi.isChecked=true;"KAKAOMAP"->b.radioKakaoMap.isChecked=true;"NAVER"->b.radioNaver.isChecked=true;else->b.radioTmap.isChecked=true}
  b.switchNavi.setOnCheckedChangeListener{_,v->AppPrefs.setNaviEnabled(this,v);status()}
  b.switchOrderAutoOpen.setOnCheckedChangeListener{_,v->AppPrefs.setOrderAutoOpenEnabled(this,v);status()}
  b.switchMusic.setOnCheckedChangeListener{_,v->AppPrefs.setMusicEnabled(this,v);if(!v)clear();status()}
  b.radioNavis.setOnCheckedChangeListener{_,id->AppPrefs.setSelectedNavi(this,when(id){R.id.radioKakaoNavi->"KAKAONAVI";R.id.radioKakaoMap->"KAKAOMAP";R.id.radioNaver->"NAVER";else->"TMAP"});status()}
  b.buttonAccessibility.setOnClickListener{ao=true;no=false;startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}
  b.buttonNotificationAccess.setOnClickListener{no=true;startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))}
 }
  override fun onResume(){super.onResume();status();b.root.post{permissions()}}
  private fun clear(){
  NotificationLogWriter.appendAutoOpenResult(this,MusicSessionHelper.YOUTUBE_MUSIC,"state_reset","music_toggle_off")
  AppPrefs.setTargetActive(this,false);AppPrefs.setAutoPaused(this,false);AppPrefs.setAutoPauseAt(this,0L);AppPrefs.setResumePending(this,false);AppPrefs.setResumeRequestedAt(this,0L)
  }
 private fun permissions(){if(!access()){if(!ao){ao=true;startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))};return};if(!notifyOK()){if(!no){no=true;startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))};return}}
 private fun status(){val n=when(AppPrefs.selectedNavi(this)){"KAKAONAVI"->"카카오내비";"KAKAOMAP"->"카카오맵";"NAVER"->"네이버지도";else->"티맵"};b.textStatus.text="내비: ${if(AppPrefs.isNaviEnabled(this))"ON" else "OFF"} ($n)\n신규 주문 화면전환: ${if(AppPrefs.isOrderAutoOpenEnabled(this))"ON" else "OFF"}\n음악: ${if(AppPrefs.isMusicEnabled(this))"ON" else "OFF"}\n접근성: ${if(access())"허용됨" else "필요"}\n알림 접근: ${if(notifyOK())"허용됨" else "필요"}"}
 private fun access():Boolean{val e=Settings.Secure.getString(contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?:return false;val q=ComponentName(this,ForegroundAppAccessibilityService::class.java).flattenToString();return e.split(':').any{it.equals(q,true)}}
 private fun notifyOK():Boolean{val e=Settings.Secure.getString(contentResolver,"enabled_notification_listeners")?:return false;val q=ComponentName(this,MusicNotificationListener::class.java).flattenToString();return e.split(':').any{it.equals(q,true)}}
}
