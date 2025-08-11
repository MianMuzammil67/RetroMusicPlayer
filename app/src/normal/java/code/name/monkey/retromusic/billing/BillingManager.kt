package code.name.monkey.retromusic.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.lifecycle.MutableLiveData
import code.name.monkey.retromusic.Constants
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.extensions.showToast
import com.android.billingclient.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BillingManager handles all Google Play Billing operations for the RetroMusicPlayer app.
 * This class manages in-app purchases for the Pro version upgrade, using Google Play Billing Library 7.0.0.
 * 
 * Key Features:
 * - Connects to Google Play Billing service
 * - Handles purchase flows for Pro version upgrade
 * - Manages purchase verification and acknowledgment (required by Google Play policy)
 * - Restores previous purchases across devices
 * - Provides proper callback mechanisms for UI state management
 * 
 * Note: This implementation uses the onBillingSetupFinished callback to enable UI buttons
 * instead of arbitrary delays, ensuring buttons are only enabled when billing is ready.
 * 
 * @param context Application context for billing operations
 */
class BillingManager(private val context: Context) {
    
    /**
     * Listener interface for billing setup completion.
     * This addresses the feedback "Can't we use this callback to enable buttons? Instead of an arbitrary delay"
     * by providing a proper callback mechanism when billing client is ready for operations.
     */
    interface BillingSetupListener {
        /**
         * Called when the billing client has successfully connected and is ready for use.
         * UI components should enable purchase/restore buttons ONLY in this callback,
         * not with arbitrary delays or immediately on activity creation.
         */
        fun onBillingSetupFinished()
    }
    
    /** Listener for billing setup events - set by UI components like PurchaseActivity */
    var billingSetupListener: BillingSetupListener? = null
    
    /** Google Play Billing client instance for all billing operations */
    private var billingClient: BillingClient
    
    /**
     * Listener for purchase updates from Google Play Billing service.
     * Handles all purchase state changes: successful purchases, cancellations, and errors.
     * This is called automatically by the billing client when purchase states change.
     */
    private val purchaseUpdateListener = PurchasesUpdatedListener { billingResult, purchases ->
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                // Purchase flow completed successfully - process all purchased items
                purchases?.forEach { purchase ->
                    handlePurchase(purchase)
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                // User cancelled the purchase dialog - this is normal behavior
                Log.d(TAG, "Purchase canceled by user")
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                // User already owns this item - refresh purchase status
                Log.d(TAG, "Item already owned - refreshing purchase status")
                queryPurchases()
            }
            else -> {
                // Handle other billing errors (network issues, item unavailable, etc.)
                Log.e(TAG, "Purchase error: ${billingResult.debugMessage}")
            }
        }
    }

    /** LiveData for Pro version ownership status - observed by UI components */
    private val _isProVersion = MutableLiveData<Boolean>()
    
    /** Public getter for Pro version status - used throughout the app */
    val isProVersion: Boolean get() = _isProVersion.value ?: false

    init {
        // Initialize billing client with purchase listener and enable pending purchases
        // enablePendingPurchases() is required for Google Play Billing Library 3.0+
        billingClient = BillingClient.newBuilder(context)
            .setListener(purchaseUpdateListener)
            .enablePendingPurchases() // Required to handle purchases made outside the app
            .build()
        
        // Start connection to Google Play Billing service immediately
        startConnection()
    }

    /**
     * Establishes connection to Google Play Billing service.
     * This must complete successfully before any billing operations can be performed.
     * Uses proper callback mechanism instead of arbitrary delays.
     */
    private fun startConnection() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Billing setup finished successfully - billing client ready")
                    
                    // Query existing purchases to restore Pro version status
                    queryPurchases()
                    
                    // IMPORTANT: Enable UI buttons only after billing is ready
                    // This replaces arbitrary delays and ensures billing operations will succeed
                    billingSetupListener?.onBillingSetupFinished()
                } else {
                    Log.e(TAG, "Billing setup failed: ${billingResult.debugMessage}")
                    // Note: UI buttons should remain disabled if setup fails
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.d(TAG, "Billing service disconnected - will auto-reconnect when needed")
                // The BillingClient will automatically attempt to reconnect when needed
                // No manual reconnection is required
            }
        })
    }

    /**
     * Queries all existing purchases to determine current Pro version ownership status.
     * This is called:
     * - On app startup (after billing setup)
     * - After successful purchases
     * - When restoring purchases
     * 
     * Updates the _isProVersion LiveData based on current purchase state.
     */
    private fun queryPurchases() {
        CoroutineScope(Dispatchers.IO).launch {
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP) // Query in-app products (not subscriptions)
                .build()
            
            billingClient.queryPurchasesAsync(params) { billingResult, purchasesList ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    // Check if user owns the Pro version product ID
                    val hasProVersion = purchasesList.any { purchase ->
                        purchase.products.contains(Constants.PRO_VERSION_PRODUCT_ID) && 
                        purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                    }
                    
                    // Update Pro version status on main thread (required for LiveData)
                    CoroutineScope(Dispatchers.Main).launch {
                        _isProVersion.value = hasProVersion
                        Log.d(TAG, "Pro version status updated: $hasProVersion")
                    }
                } else {
                    Log.e(TAG, "Failed to query purchases: ${billingResult.debugMessage}")
                }
            }
        }
    }

    /**
     * Processes a completed purchase from Google Play.
     * Handles purchase acknowledgment (required by Google Play policy) and updates app state.
     * 
     * Important: All purchases must be acknowledged within 3 days or they will be refunded.
     * 
     * @param purchase The purchase object received from Google Play
     */
    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            // Acknowledge the purchase if not already acknowledged
            // This is REQUIRED by Google Play policy to finalize the purchase
            if (!purchase.isAcknowledged) {
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                
                CoroutineScope(Dispatchers.IO).launch {
                    billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
                        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                            Log.d(TAG, "Purchase acknowledged successfully")
                        } else {
                            Log.e(TAG, "Failed to acknowledge purchase: ${billingResult.debugMessage}")
                        }
                    }
                }
            }
            
            // Update Pro version status if this is the Pro version purchase
            if (purchase.products.contains(Constants.PRO_VERSION_PRODUCT_ID)) {
                _isProVersion.value = true
                context.showToast(R.string.thank_you) // Show appreciation message to user
                Log.d(TAG, "Pro version purchased successfully")
            }
        } else {
            Log.d(TAG, "Purchase state: ${purchase.purchaseState}")
        }
    }

    /**
     * Initiates the purchase flow for the Pro version upgrade.
     * 
     * Flow:
     * 1. Query product details from Google Play
     * 2. Build billing flow parameters
     * 3. Launch Google Play billing UI
     * 4. Handle result in purchaseUpdateListener
     * 
     * @param activity The activity that will host the billing flow UI
     */
    fun launchBillingFlow(activity: Activity) {
        CoroutineScope(Dispatchers.IO).launch {
            // First, query product details for the Pro version from Google Play
            val productDetailsParams = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(Constants.PRO_VERSION_PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()

            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(listOf(productDetailsParams))
                .build()

            billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    val productDetails = productDetailsList.firstOrNull()
                    if (productDetails != null) {
                        // Build billing flow parameters with valid product details
                        val productDetailsParamsList = listOf(
                            BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(productDetails)
                                .build()
                        )

                        val billingFlowParams = BillingFlowParams.newBuilder()
                            .setProductDetailsParamsList(productDetailsParamsList)
                            .build()

                        // Launch billing flow on main thread (UI operation)
                        CoroutineScope(Dispatchers.Main).launch {
                            val result = billingClient.launchBillingFlow(activity, billingFlowParams)
                            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                                Log.e(TAG, "Failed to launch billing flow: ${result.debugMessage}")
                            } else {
                                Log.d(TAG, "Billing flow launched successfully")
                            }
                        }
                    } else {
                        Log.e(TAG, "Product details not found for Pro version - check product ID in Play Console")
                    }
                } else {
                    Log.e(TAG, "Failed to query product details: ${billingResult.debugMessage}")
                }
            }
        }
    }

    /**
     * Restores previous purchases by re-querying Google Play for owned items.
     * This is useful when:
     * - User reinstalls the app
     * - User switches devices
     * - User claims they purchased but Pro features are not unlocked
     * 
     * Shows user feedback during the restoration process.
     */
    fun restorePurchases() {
        context.showToast(R.string.restoring_purchase)
        Log.d(TAG, "Restoring purchases...")
        queryPurchases() // Re-query all purchases to update Pro status
    }

    /**
     * Disconnects from Google Play Billing service and cleans up resources.
     * Should be called when the billing manager is no longer needed (e.g., app shutdown).
     * 
     * Note: The billing client will automatically reconnect when needed for future operations.
     */
    fun release() {
        if (billingClient.isReady) {
            billingClient.endConnection()
            Log.d(TAG, "Billing client connection ended")
        }
    }

    companion object {
        /** Tag for logging all billing operations */
        private const val TAG = "BillingManager"
    }
}