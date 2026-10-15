package kr.deliveryauto.app

import android.content.Context
import android.provider.Settings
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Android ID + Firebase Firestore 기반 체험판 관리.
 * - 기기 식별: Android ID (구글 계정 변경과 무관)
 * - 서버 기록: Firestore에 기기 ID별 체험 시작일 저장 (재설치/데이터 삭제 우회 방지)
 * - 로컬 캐시: AppPrefs에 시작일 캐시 (오프라인 동작 보장)
 */
object TrialManager {

    private const val COLLECTION = "trials"
    private const val FIELD_START_DATE = "startDate"

    @Volatile
    private var verifying = false

    /** 기기 고유 Android ID */
    fun getDeviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    /**
     * 체험판 등록 또는 기존 기록 확인.
     * - Firestore에 이 기기 ID가 있으면 → 서버 시작일을 로컬에 동기화
     * - 없으면 → 새로 등록 (현재 시각)
     * - 네트워크 실패 시 → 로컬에 기록이 없으면 현재 시각으로 로컬만 저장
     *
     * @param onComplete true=기능 사용 가능(체험 중 or 구매), false=만료
     */
    fun registerOrVerify(context: Context, onComplete: (Boolean) -> Unit) {
        if (AppPrefs.isPurchased(context)) {
            onComplete(true)
            return
        }

        // 동시 호출 방지
        synchronized(this) {
            if (verifying) {
                onComplete(AppPrefs.isFeatureUnlocked(context))
                return
            }
            verifying = true
        }

        val deviceId = getDeviceId(context)
        val db = FirebaseFirestore.getInstance()
        val docRef = db.collection(COLLECTION).document(deviceId)

        docRef.get()
            .addOnSuccessListener { doc ->
                synchronized(this) {
                    val serverStart = doc.getLong(FIELD_START_DATE)
                    if (serverStart != null) {
                        // 기존 기록 있음 → 서버 시작일 사용 (우회 방지)
                        AppPrefs.setTrialStartDate(context, serverStart)
                    } else {
                        // 신규 기기 → 등록
                        val now = System.currentTimeMillis()
                        docRef.set(mapOf(FIELD_START_DATE to now))
                        AppPrefs.setTrialStartDate(context, now)
                    }
                    verifying = false
                }
                onComplete(AppPrefs.isFeatureUnlocked(context))
            }
            .addOnFailureListener {
                synchronized(this) {
                    // 오프라인 — 로컬에 기록이 없으면 시작
                    if (AppPrefs.trialStartDate(context) == 0L) {
                        AppPrefs.setTrialStartDate(context, System.currentTimeMillis())
                    }
                    verifying = false
                }
                onComplete(AppPrefs.isFeatureUnlocked(context))
            }
    }
}
