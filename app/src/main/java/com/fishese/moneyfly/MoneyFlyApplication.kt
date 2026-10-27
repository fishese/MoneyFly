package com.fishese.moneyfly

import android.app.Application
import com.fishese.moneyfly.storage.RoomLedgerStore
import com.fishese.moneyfly.capture.AndroidCaptureController

class MoneyFlyApplication : Application() {
    val appLock: com.fishese.moneyfly.security.AppLockManager by lazy { com.fishese.moneyfly.security.AppLockManager(this) }
    val fxRepository: com.fishese.moneyfly.fx.FxRepository by lazy { com.fishese.moneyfly.fx.FxRepository(this) }
    val ledgerStore: RoomLedgerStore by lazy { RoomLedgerStore.open(this) }
    val captureController: AndroidCaptureController by lazy { AndroidCaptureController(this, ledgerStore, ledgerStore) }
}
