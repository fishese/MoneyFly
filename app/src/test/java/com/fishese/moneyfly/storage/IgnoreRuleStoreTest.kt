package com.fishese.moneyfly.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fishese.moneyfly.capture.*
import com.fishese.moneyfly.ledger.*
import com.fishese.moneyfly.recognition.*
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IgnoreRuleStoreTest {
    private lateinit var context: Context
    private lateinit var database: LedgerDatabase
    private lateinit var store: RoomLedgerStore
    private val now = Instant.parse("2026-10-25T01:00:00Z")
    private val filename = "ignore-rule-store.db"
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(filename)
        database = Room.databaseBuilder(context, LedgerDatabase::class.java, filename).build()
        store = RoomLedgerStore(database, ReceiptDigester { it.reversed() }, { now })
    }
    @After fun cleanup() { database.close(); context.deleteDatabase(filename) }
    private fun payload(key: String, source: String = "synthetic.app", title: String = "Charging battery is full") =
        NotificationPayload(source, "synthetic-user", key, now, now, title = title)
    private suspend fun capture(key: String, source: String = "synthetic.app", title: String = "Charging battery is full"): InboxItem {
        val id = store.execute(LedgerCommand.CaptureNotification(payload(key, source, title))).entityId!!
        return store.inbox.first().single { it.id == id }
    }

    @Test fun enabledRuleDynamicallyHidesOnlyPendingMatchesAndDisableRestoresThem() = runBlocking {
        assertTrue(store.execute(LedgerCommand.SetCaptureConsent(true, 0)).applied)
        val matching = capture("match")
        val otherText = capture("other", title = "Synthetic purchase")
        val otherApp = capture("app", source = "other.app")
        val rule = IgnoreRule("rule", "Synthetic charging", "synthetic.app", MessageField.TITLE, "Charging * full*")
        assertTrue(store.execute(LedgerCommand.PutIgnoreRule(rule, operationId = "put-rule")).applied)
        val hidden = store.inbox.first()
        assertEquals(InboxStatus.IGNORED, hidden.single { it.id == matching.id }.status)
        assertEquals("rule", hidden.single { it.id == matching.id }.ignoreRuleId)
        assertEquals(InboxStatus.UNRECOGNIZED, hidden.single { it.id == otherText.id }.status)
        assertEquals(InboxStatus.UNRECOGNIZED, hidden.single { it.id == otherApp.id }.status)
        assertTrue(store.execute(LedgerCommand.SetIgnoreRuleEnabled("rule", 1, false, operationId = "disable-rule")).applied)
        val restored = store.inbox.first().single { it.id == matching.id }
        assertEquals(InboxStatus.UNRECOGNIZED, restored.status)
        assertNull(restored.ignoreRuleId)
    }

    @Test fun ruleNeverReclassifiesSavedItemAndCommandsAreRevisionedAndIdempotent() = runBlocking {
        store.execute(LedgerCommand.SetCaptureConsent(true, 0))
        val item = capture("saved")
        val saved = store.execute(LedgerCommand.SaveCaptured(item.id, item.revision,
            EntryDraft("1.00", occurredAt = now, merchant = "Synthetic"), entryId = "entry", operationId = "save"))
        assertTrue(saved.applied)
        val command = LedgerCommand.PutIgnoreRule(IgnoreRule("rule", "Synthetic", "synthetic.app", MessageField.TITLE, "Charging*"), operationId = "rule-operation")
        assertTrue(store.execute(command).applied)
        assertTrue(store.execute(command).replayed)
        assertEquals(InboxStatus.SAVED, store.inbox.first().single().status)
        assertEquals(CommandStatus.CONFLICT, store.execute(LedgerCommand.SetIgnoreRuleEnabled("rule", 0, false)).status)
        assertTrue(store.execute(LedgerCommand.DeleteIgnoreRule("rule", 1)).applied)
        assertTrue(store.ignoreRules.first().isEmpty())
        assertEquals(CommandStatus.INVALID, store.execute(LedgerCommand.PutIgnoreRule(command.rule)).status)
    }

    @Test fun invalidCatchAllPatternIsRejected() = runBlocking {
        val invalid = IgnoreRule("rule", "Synthetic", "synthetic.app", MessageField.TITLE, "***")
        assertEquals(CommandStatus.INVALID, store.execute(LedgerCommand.PutIgnoreRule(invalid)).status)
        assertTrue(store.ignoreRules.first().isEmpty())
    }

    @Test fun summaryRuleUsesFlagAndScopeAndCanRestorePendingItems() = runBlocking {
        store.execute(LedgerCommand.SetCaptureConsent(true, 0))
        val summary = store.execute(LedgerCommand.CaptureNotification(payload("summary", title = "Invented grouped messages").copy(groupSummary = true))).entityId
        val individual = capture("individual", title = "Invented grouped messages")
        val other = store.execute(LedgerCommand.CaptureNotification(payload("other-summary", "other.app").copy(groupSummary = true))).entityId
        val rule = IgnoreRule("summaries", "Synthetic summaries", "synthetic.app", MessageField.TITLE, "", summaryOnly = true)
        assertTrue(store.execute(LedgerCommand.PutIgnoreRule(rule)).applied)
        assertTrue(store.ignoreRules.first().single().summaryOnly)
        assertEquals(InboxStatus.IGNORED, store.inbox.first().single { it.id == summary }.status)
        assertEquals(InboxStatus.UNRECOGNIZED, store.inbox.first().single { it.id == individual.id }.status)
        assertEquals(InboxStatus.UNRECOGNIZED, store.inbox.first().single { it.id == other }.status)
        assertTrue(store.execute(LedgerCommand.PutIgnoreRule(rule.copy(sameAppOnly = false), 1)).applied)
        assertEquals(InboxStatus.IGNORED, store.inbox.first().single { it.id == other }.status)
        store.execute(LedgerCommand.SetIgnoreRuleEnabled(rule.id, 2, false))
        assertEquals(InboxStatus.UNRECOGNIZED, store.inbox.first().single { it.id == summary }.status)
    }

    @Test fun sameAppIsDefaultAndCrossAppIsExplicitAndPersisted() = runBlocking {
        store.execute(LedgerCommand.SetCaptureConsent(true, 0))
        val other = capture("cross-app", source = "other.app")
        val scoped = IgnoreRule("scoped", "Scoped synthetic", "synthetic.app", MessageField.TITLE, "Charging*")
        assertTrue(store.execute(LedgerCommand.PutIgnoreRule(scoped, operationId = "scoped-put")).applied)
        assertEquals(InboxStatus.UNRECOGNIZED, store.inbox.first().single { it.id == other.id }.status)
        val crossApp = scoped.copy(id = "cross", name = "Cross-app synthetic", sameAppOnly = false)
        assertTrue(store.execute(LedgerCommand.PutIgnoreRule(crossApp, operationId = "cross-put")).applied)
        assertEquals(InboxStatus.IGNORED, store.inbox.first().single { it.id == other.id }.status)
        assertFalse(store.ignoreRules.first().single { it.id == "cross" }.sameAppOnly)
    }

    @Test fun defaultScopeKeepsLegacyOperationFingerprintShape() {
        val rule = IgnoreRule("rule", "Synthetic", "synthetic.app", MessageField.TITLE, "Synthetic*")
        val legacyShape = CommandFingerprint.canonical(LedgerCommand.PutIgnoreRule(rule, operationId = "operation"))
        assertFalse(legacyShape.contains("cross-app"))
        val crossApp = CommandFingerprint.canonical(LedgerCommand.PutIgnoreRule(rule.copy(sameAppOnly = false), operationId = "operation"))
        assertTrue(crossApp.contains("cross-app"))
        assertNotEquals(legacyShape, crossApp)
    }

    @Test fun chargingDefaultIsReversibleAndNeverResurrectsAfterUserChoice() = runBlocking {
        store.execute(LedgerCommand.SetCaptureConsent(true, 0))
        val first = capture("status-one", source = "com.android.systemui")
        assertEquals(InboxStatus.IGNORED, first.status)
        val default = store.ignoreRules.first().single { it.id.endsWith(":charging-full") }
        assertTrue(default.id.startsWith("default-status:"))

        assertTrue(store.execute(LedgerCommand.SetIgnoreRuleEnabled(default.id, default.revision, false)).applied)
        assertEquals(InboxStatus.UNRECOGNIZED, store.inbox.first().single { it.id == first.id }.status)
        val second = capture("status-two", source = "com.android.systemui")
        assertEquals(InboxStatus.UNRECOGNIZED, second.status)

        val disabled = store.ignoreRules.first().single { it.id == default.id }
        assertTrue(store.execute(LedgerCommand.DeleteIgnoreRule(disabled.id, disabled.revision)).applied)
        val third = capture("status-three", source = "com.android.systemui")
        assertEquals(InboxStatus.UNRECOGNIZED, third.status)
        assertTrue(store.ignoreRules.first().none { it.id == default.id })
    }

    @Test fun defaultPowerRulesAreScopedAndReversible() = runBlocking {
        store.execute(LedgerCommand.SetCaptureConsent(true, 0))
        val system = capture("power-system", "com.android.systemui", "Power disconnected")
        val payment = capture("power-payment", "synthetic.app", "Power disconnected")
        assertEquals(InboxStatus.IGNORED, system.status)
        assertEquals(InboxStatus.UNRECOGNIZED, payment.status)
        val rule = store.ignoreRules.first().single { it.id.endsWith(":power-disconnected") }
        store.execute(LedgerCommand.SetIgnoreRuleEnabled(rule.id, rule.revision, false))
        assertEquals(InboxStatus.UNRECOGNIZED, store.inbox.first().single { it.id == system.id }.status)
    }

    @Test fun ignoredSourceDefaultsKeepExplicitChoicesAndBlockCapture() = runBlocking {
        val sql = database.openHelper.writableDatabase
        seedDefaultIgnoredSources(sql)
        store.execute(LedgerCommand.SetCaptureConsent(true, 0))
        val source = store.sources.first().single { it.sourcePackage == "com.instagram.android" }
        assertTrue(source.ignored)
        assertTrue(store.execute(LedgerCommand.CaptureNotification(payload("ignored", source.sourcePackage))).applied)
        assertTrue(store.inbox.first().isEmpty())
        assertTrue(store.execute(LedgerCommand.SetIgnoredSource(source.sourcePackage, false, source.revision)).applied)
        seedDefaultIgnoredSources(sql)
        assertFalse(store.sources.first().single { it.sourcePackage == source.sourcePackage }.ignored)
        assertEquals(InboxStatus.UNRECOGNIZED, capture("restored", source.sourcePackage).status)
    }
}
