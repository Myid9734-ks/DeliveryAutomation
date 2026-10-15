package kr.deliveryauto.app

import android.app.Activity
import android.widget.Toast
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetailsResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Google Play Billing 일회성 구매 관리.
 * Play Console에서 상품 ID [AppConstants.PRODUCT_ID_PREMIUM]으로 인앱 상품 등록 필요.
 */
class BillingManager(
    private val activity: Activity,
    private val onPurchaseComplete: (success: Boolean) -> Unit
) {

    private val scope = CoroutineScope(Dispatchers.Main)

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { handlePurchase(it) }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                NotificationLogWriter.appendDebugEvent(
                    activity, "purchase_canceled"
                )
            }
            else -> {
                NotificationLogWriter.appendDebugEvent(
                    activity, "purchase_failed",
                    "responseCode" to billingResult.responseCode,
                    "debugMessage" to billingResult.debugMessage
                )
                onPurchaseComplete(false)
            }
        }
    }

    private val billingClient: BillingClient = BillingClient.newBuilder(activity)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(
            com.android.billingclient.api.PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    private var cachedProductDetails: com.android.billingclient.api.ProductDetails? = null

    /** Billing 서비스 연결 시작 */
    fun startConnection() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    checkExistingPurchases()
                    queryProduct()
                } else {
                    NotificationLogWriter.appendDebugEvent(
                        activity, "billing_setup_failed",
                        "responseCode" to billingResult.responseCode,
                        "debugMessage" to billingResult.debugMessage
                    )
                }
            }

            override fun onBillingServiceDisconnected() {
                // 재연결은 다음 앱 실행 시 자동으로 됨
            }
        })
    }

    /** 기존 구매 확인 (앱 재설치 후에도 구매 복원) */
    private fun checkExistingPurchases() {
        scope.launch {
            try {
                val params = QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
                val result = billingClient.queryPurchasesAsync(params)
                for (purchase in result.purchasesList) {
                    if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        AppPrefs.setPurchased(activity, true)
                        acknowledgePurchase(purchase)
                        onPurchaseComplete(true)
                        return@launch
                    }
                }
            } catch (e: Exception) {
                NotificationLogWriter.appendDebugEvent(
                    activity, "check_purchases_failed",
                    "error" to (e.message ?: "unknown")
                )
            }
        }
    }

    /** 상품 정보 조회 */
    private fun queryProduct() {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(AppConstants.PRODUCT_ID_PREMIUM)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()

        scope.launch {
            try {
                val result: ProductDetailsResult = billingClient.queryProductDetails(params)
                if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    cachedProductDetails = result.productDetailsList?.firstOrNull()
                } else {
                    NotificationLogWriter.appendDebugEvent(
                        activity, "product_query_failed",
                        "responseCode" to result.billingResult.responseCode
                    )
                }
            } catch (e: Exception) {
                NotificationLogWriter.appendDebugEvent(
                    activity, "product_query_error",
                    "error" to (e.message ?: "unknown")
                )
            }
        }
    }

    /** 구매 화면 실행 */
    fun launchPurchase() {
        if (!billingClient.isReady) {
            Toast.makeText(activity, "결제 서비스에 연결 중입니다. 잠시 후 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
            startConnection()
            return
        }

        val productDetails = cachedProductDetails
        if (productDetails == null) {
            NotificationLogWriter.appendDebugEvent(
                activity, "billing_launch_failed",
                "reason" to "product_details_null"
            )
            Toast.makeText(activity, "상품 정보를 불러오는 중입니다. 잠시 후 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
            queryProduct()
            return
        }

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .build()
                )
            )
            .build()

        val result = billingClient.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            NotificationLogWriter.appendDebugEvent(
                activity, "billing_flow_failed",
                "responseCode" to result.responseCode
            )
            onPurchaseComplete(false)
        }
    }

    /** 구매 처리 및 확인 */
    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            AppPrefs.setPurchased(activity, true)
            acknowledgePurchase(purchase)
            onPurchaseComplete(true)
            NotificationLogWriter.appendDebugEvent(
                activity, "purchase_success",
                "orderId" to (purchase.orderId ?: "")
            )
        }
    }

    /** 구매 확인 (acknowledge) — 3일 내 미확인 시 자동 환불됨 */
    private fun acknowledgePurchase(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { result ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                NotificationLogWriter.appendDebugEvent(
                    activity, "purchase_acknowledged"
                )
            } else {
                // acknowledge 실패 시 다음 앱 실행 때 checkExistingPurchases에서 재시도
                NotificationLogWriter.appendDebugEvent(
                    activity, "acknowledge_failed",
                    "responseCode" to result.responseCode,
                    "debugMessage" to result.debugMessage
                )
            }
        }
    }

    /** 가격 문자열 반환 (예: "₩10,000") */
    fun getFormattedPrice(): String? =
        cachedProductDetails?.oneTimePurchaseOfferDetails?.formattedPrice

    fun endConnection() {
        billingClient.endConnection()
    }
}
