package com.fishese.moneyfly

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.fishese.moneyfly.ui.MoneyFlyApp
import com.fishese.moneyfly.capture.InboxRetentionWorker
import com.fishese.moneyfly.ui.seedExpenseCategories
import com.fishese.moneyfly.ui.seedTransportRules
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        InboxRetentionWorker.schedule(this)
        com.fishese.moneyfly.fx.FxRateWorker.schedule(this)
        val store = (application as MoneyFlyApplication).ledgerStore
        lifecycleScope.launch(Dispatchers.IO) {
            if (seedExpenseCategories(this@MainActivity, store)) seedTransportRules(this@MainActivity, store)
        }
        setContent { MoneyFlyApp(store, (application as MoneyFlyApplication).captureController, (application as MoneyFlyApplication).fxRepository) }
    }

    override fun onResume() {
        super.onResume()
        (application as MoneyFlyApplication).captureController.onForeground()
    }
}
