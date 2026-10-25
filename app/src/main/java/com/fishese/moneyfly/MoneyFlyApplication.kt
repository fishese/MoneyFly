package com.fishese.moneyfly

import android.app.Application
import com.fishese.moneyfly.storage.RoomLedgerStore
import com.fishese.moneyfly.capture.AndroidCaptureController

class MoneyFlyApplication : Application() {
    val ledgerStore: RoomLedgerStore by lazy { RoomLedgerStore.open(this) }
    val captureController: AndroidCaptureController by lazy { AndroidCaptureController(this, ledgerStore, ledgerStore) }
}
