package com.fishese.moneyfly

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.fishese.moneyfly.ui.MoneyFlyApp
import com.fishese.moneyfly.capture.InboxRetentionWorker
import com.fishese.moneyfly.ui.seedExpenseCategories
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        InboxRetentionWorker.schedule(this)
        val store = (application as MoneyFlyApplication).ledgerStore
        lifecycleScope.launch(Dispatchers.IO) { seedExpenseCategories(this@MainActivity, store) }
        setContent { MoneyFlyApp(store, (application as MoneyFlyApplication).captureController) }
    }

    override fun onResume() {
        super.onResume()
        (application as MoneyFlyApplication).captureController.onForeground()
    }
}
