package com.fishese.moneyfly.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fishese.moneyfly.ledger.*
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
class RoomLedgerStoreTest {
    private lateinit var context: Context
    private lateinit var db: LedgerDatabase
    private lateinit var store: RoomLedgerStore
    private val filename = "ledger-writer-test.db"
    private val draft = EntryDraft("12.34", occurredAt = Instant.parse("2026-10-01T12:00:00Z"), merchant = "Synthetic Shop")
    @Before fun open() { context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(filename); reopen() }
    private fun reopen() { db = Room.databaseBuilder(context, LedgerDatabase::class.java, filename).build(); store = RoomLedgerStore(db) }
    @After fun close() { db.close(); context.deleteDatabase(filename) }

    @Test fun saveReplayPersistsAcrossDatabaseReopenAndRejectsOperationReuse() = runBlocking {
        val command = LedgerCommand.Save(draft, "entry", "save")
        val first = store.execute(command)
        assertTrue(first.applied)
        db.close(); reopen()
        val replay = store.execute(command)
        assertTrue(replay.replayed)
        assertEquals(first.entry, replay.entry)
        assertEquals(1, store.entries.first().size)
        assertEquals(CommandStatus.INVALID, store.execute(command.copy(draft = draft.copy(amount = "99"))).status)
        assertEquals("12.34", store.entries.first().single().draft.amount)
    }
    @Test fun newExpenseUsesRulesButExplicitChoicesConflictsAndOtherDirectionsStaySafe() = runBlocking {
        store.execute(LedgerCommand.PutCategory("travel", "Travel"))
        store.execute(LedgerCommand.PutCategory("other", "Other"))
        store.execute(LedgerCommand.PutRule(CategoryRule("travel-rule", RuleField.MERCHANT, LiteralMatch.EQUALS, "Synthetic Shop", "travel")))
        val automatic = store.execute(LedgerCommand.Save(draft)).entry!!.draft
        assertEquals("travel", automatic.categoryId); assertEquals(CategoryOrigin.RULE, automatic.categoryOrigin)
        assertEquals("other", store.execute(LedgerCommand.Save(draft.copy(categoryId = "other", categoryOrigin = CategoryOrigin.EXPLICIT))).entry!!.draft.categoryId)
        assertNull(store.execute(LedgerCommand.Save(draft.copy(categoryOrigin = CategoryOrigin.EXPLICIT))).entry!!.draft.categoryId)
        assertNull(store.execute(LedgerCommand.Save(draft.copy(kind = EntryKind.INCOME))).entry!!.draft.categoryId)
        store.execute(LedgerCommand.PutRule(CategoryRule("conflict", RuleField.MERCHANT, LiteralMatch.EQUALS, "Synthetic Shop", "other")))
        assertNull(store.execute(LedgerCommand.Save(draft)).entry!!.draft.categoryId)
    }
    @Test fun accountRulesRespectExplicitChoiceSpecificityAndDeletion() = runBlocking {
        store.execute(LedgerCommand.PutAccount("app-account", "Synthetic app account"))
        store.execute(LedgerCommand.PutAccount("merchant-account", "Synthetic merchant account"))
        store.execute(LedgerCommand.PutCategory("category", "Synthetic category"))
        val appRule = AccountRule("app-rule", null, "synthetic.app", "app-account")
        val merchantRule = AccountRule("merchant-rule", "Synthetic Shop", null, "merchant-account")
        assertTrue(store.execute(LedgerCommand.PutAccountRule(appRule)).applied)
        assertTrue(store.execute(LedgerCommand.PutAccountRule(merchantRule)).applied)
        store.execute(LedgerCommand.PutRule(CategoryRule("scoped", RuleField.MERCHANT, LiteralMatch.EQUALS, "Synthetic Shop", "category", accountId = "merchant-account")))
        val fromApp = draft.copy(sourcePackage = "synthetic.app")
        val saved = store.execute(LedgerCommand.Save(fromApp)).entry!!.draft
        assertEquals("merchant-account", saved.accountId)
        assertEquals("category", saved.categoryId)
        assertEquals("app-account", store.execute(LedgerCommand.Save(fromApp.copy(merchant = "Different"))).entry!!.draft.accountId)
        assertEquals("app-account", store.execute(LedgerCommand.Save(fromApp.copy(accountId = "app-account"))).entry!!.draft.accountId)
        assertNull(store.execute(LedgerCommand.Save(fromApp.copy(suppressAccountRule = true))).entry!!.draft.accountId)
        assertTrue(store.execute(LedgerCommand.DeleteAccountRule("merchant-rule", 1)).applied)
        assertEquals("app-account", store.execute(LedgerCommand.Save(fromApp)).entry!!.draft.accountId)
        assertTrue(store.execute(LedgerCommand.DeleteAccount("app-account", 1)).applied)
        assertTrue(store.accountRules.first().isEmpty())
        assertEquals(CommandStatus.INVALID, store.execute(LedgerCommand.PutAccountRule(appRule)).status)
    }
    @Test fun conflictingAccountRulesLeaveAccountUnassigned() = runBlocking {
        store.execute(LedgerCommand.PutAccount("one", "First"))
        store.execute(LedgerCommand.PutAccount("two", "Second"))
        store.execute(LedgerCommand.PutAccountRule(AccountRule("r1", "Synthetic Shop", null, "one")))
        store.execute(LedgerCommand.PutAccountRule(AccountRule("r2", "synthetic shop", null, "two")))
        assertNull(store.execute(LedgerCommand.Save(draft)).entry!!.draft.accountId)
    }
    @Test fun savingCategoryRuleClassifiesOnlyMatchingUncategorizedExpensesAndReplaysSafely() = runBlocking {
        store.execute(LedgerCommand.PutCategory("travel", "Travel"))
        store.execute(LedgerCommand.PutCategory("other", "Other"))
        store.execute(LedgerCommand.Save(draft, "matching"))
        store.execute(LedgerCommand.Save(draft.copy(merchant = "Different"), "unmatched"))
        store.execute(LedgerCommand.Save(draft.copy(categoryId = "other", categoryOrigin = CategoryOrigin.EXPLICIT), "chosen"))
        store.execute(LedgerCommand.Save(draft.copy(kind = EntryKind.INCOME), "income"))
        val rule = CategoryRule("travel-rule", RuleField.MERCHANT, LiteralMatch.EQUALS, "Synthetic Shop", "travel")
        val command = LedgerCommand.PutRule(rule, operationId = "synthetic-backfill-rule")
        assertTrue(store.execute(command).applied)
        val entries = store.entries.first().associateBy { it.id }
        assertEquals("travel", entries.getValue("matching").draft.categoryId)
        assertEquals(CategoryOrigin.RULE, entries.getValue("matching").draft.categoryOrigin)
        assertEquals(2L, entries.getValue("matching").revision)
        assertNull(entries.getValue("unmatched").draft.categoryId)
        assertEquals("other", entries.getValue("chosen").draft.categoryId)
        assertNull(entries.getValue("income").draft.categoryId)
        assertTrue(store.execute(command).replayed)
        assertEquals(2L, store.entries.first().single { it.id == "matching" }.revision)
    }

    @Test fun savingConflictingCategoryRuleDoesNotBackfillAmbiguousEntries() = runBlocking {
        store.execute(LedgerCommand.PutCategory("first", "First"))
        store.execute(LedgerCommand.PutCategory("second", "Second"))
        store.execute(LedgerCommand.PutRule(CategoryRule("first-rule", RuleField.MERCHANT, LiteralMatch.EQUALS, "Synthetic Shop", "first")))
        store.execute(LedgerCommand.Save(draft.copy(categoryOrigin = CategoryOrigin.EXPLICIT), "intentional"))
        store.execute(LedgerCommand.PutRule(CategoryRule("second-rule", RuleField.MERCHANT, LiteralMatch.EQUALS, "Synthetic Shop", "second")))
        assertNull(store.entries.first().single().draft.categoryId)
    }
    @Test fun concurrentEditsConflictAndUndoIsConditional() = runBlocking {
        val entry = store.execute(LedgerCommand.Save(draft, "entry", "save")).entry!!
        val commands = listOf(LedgerCommand.Edit(entry.id, 1, draft.copy(notes = "one"), "edit-one"), LedgerCommand.Edit(entry.id, 1, draft.copy(notes = "two"), "edit-two"))
        val results = coroutineScope { commands.map { async { store.execute(it) } }.map { it.await() } }
        assertEquals(1, results.count { it.applied })
        assertEquals(1, results.count { it.status == CommandStatus.CONFLICT })
        val winner = results.first { it.applied }.entry!!
        val undo = store.execute(LedgerCommand.Undo(winner.lastOperationId, winner.revision, "undo"))
        assertTrue(undo.applied)
        assertEquals("", undo.entry!!.draft.notes)
        assertEquals(CommandStatus.CONFLICT, store.execute(LedgerCommand.Undo(winner.lastOperationId, winner.revision, "stale-undo")).status)
    }
    @Test fun deletionTombstoneAndOriginalSaveReplayNeverResurrect() = runBlocking {
        val save = LedgerCommand.Save(draft, "entry", "save")
        store.execute(save)
        val delete = LedgerCommand.Delete("entry", 1, "delete")
        val removed = store.execute(delete)
        assertTrue(removed.entry!!.deleted)
        assertTrue(store.entries.first().isEmpty())
        assertTrue(store.execute(save).replayed)
        assertTrue(store.entries.first().isEmpty())
        assertEquals(CommandStatus.CONFLICT, store.execute(LedgerCommand.Save(draft, "entry", "new-save")).status)
        val restored = store.execute(LedgerCommand.Undo("delete", 2, "undo-delete"))
        assertEquals("entry", restored.entry!!.id)
        assertEquals(3L, restored.entry!!.revision)
        assertEquals(1, store.entries.first().size)
    }
    @Test fun labelDeletionNullsReferencesAndInvalidatesStaleEditorsAtomically() = runBlocking {
        store.execute(LedgerCommand.PutCategory("category", "Synthetic category", operationId = "cat"))
        store.execute(LedgerCommand.PutAccount("account", "Synthetic card", AccountKind.CARD, operationId = "acct"))
        store.execute(LedgerCommand.PutRule(CategoryRule("rule", RuleField.NOTES, LiteralMatch.CONTAINS, "sample", "category", accountId = "account"), operationId = "rule"))
        store.execute(LedgerCommand.Save(draft.copy(categoryId = "category", accountId = "account"), "entry", "save"))
        assertTrue(store.execute(LedgerCommand.DeleteCategory("category", 1, "delete-cat")).applied)
        val current = store.entries.first().single()
        assertNull(current.draft.categoryId)
        assertTrue(store.rules.first().isEmpty())
        assertEquals(CommandStatus.CONFLICT, store.execute(LedgerCommand.Edit("entry", 1, draft, "stale-edit")).status)
        assertTrue(store.execute(LedgerCommand.DeleteAccount("account", 1, "delete-acct")).applied)
        assertNull(store.entries.first().single().draft.accountId)
    }
    @Test fun invalidSaveDoesNotPartiallyCommitAndOutcomeReplayIsStable() = runBlocking {
        val bad = LedgerCommand.Save(draft.copy(amount = "1.001"), "invalid", "invalid-op")
        assertEquals(CommandStatus.INVALID, store.execute(bad).status)
        assertTrue(store.entries.first().isEmpty())
        assertTrue(store.execute(bad).replayed)
        assertEquals(CommandStatus.INVALID, store.execute(LedgerCommand.Save(draft.copy(occurredAt = Instant.MAX))).status)
        val fallback = draft.copy(merchant = "", timeOrigin = TimeOrigin.NOTIFICATION_POSTED, timePrecision = TimePrecision.UNKNOWN)
        assertTrue(store.execute(LedgerCommand.Save(fallback)).applied)
    }
    @Test fun operationInsertFailureRollsBackEntryMutation() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_operation BEFORE INSERT ON operation_results BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        val command = LedgerCommand.Save(draft, "entry", "save")
        var failed = false
        try { store.execute(command) } catch (_: Exception) { failed = true }
        assertTrue(failed)
        assertTrue(store.entries.first().isEmpty())
        assertNull(db.ledgerDao().operation("save"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_operation")
        assertTrue(store.execute(command).applied)
    }
    @Test fun deletedIdsCannotResetRevisionsAndAccountRuleScopeNeverBroadens() = runBlocking {
        store.execute(LedgerCommand.PutCategory("category", "Synthetic", operationId = "category"))
        store.execute(LedgerCommand.PutAccount("account", "Synthetic", operationId = "account"))
        val rule = CategoryRule("rule", RuleField.NOTES, LiteralMatch.CONTAINS, "sample", "category", accountId = "account")
        store.execute(LedgerCommand.PutRule(rule, operationId = "rule"))
        store.execute(LedgerCommand.DeleteAccount("account", 1, "delete-account"))
        assertTrue(store.rules.first().isEmpty())
        assertEquals(CommandStatus.INVALID, store.execute(LedgerCommand.PutAccount("account", "Recreated", operationId = "recreate")).status)
        assertEquals(CommandStatus.INVALID, store.execute(LedgerCommand.PutRule(rule.copy(accountId = null), operationId = "recreate-rule")).status)
        assertEquals(CommandStatus.NOT_FOUND, store.execute(LedgerCommand.PutAccount("account", "Stale", expectedRevision = 1, operationId = "stale")).status)
    }
    @Test fun aliasesRequireConfirmedUniqueNormalizedSpelling() = runBlocking {
        assertTrue(store.execute(LedgerCommand.PutAlias("a", "Synthetic\u00a0 Store", "Canonical", operationId = "one")).applied)
        assertEquals(CommandStatus.INVALID, store.execute(LedgerCommand.PutAlias("b", "  synthetic  store ", "Other", operationId = "two")).status)
        assertEquals(1, store.aliases.first().size)
    }
    @Test fun dataClassStringCollisionCannotReplayDifferentPayload() = runBlocking {
        val first = LedgerCommand.Save(draft.copy(merchant = "a, notes=b", notes = "c"), "entry", "same-operation")
        val different = first.copy(draft = draft.copy(merchant = "a", notes = "b, notes=c"))
        assertEquals(first.toString(), different.toString())
        assertNotEquals(CommandFingerprint.of(first), CommandFingerprint.of(different))
        assertTrue(store.execute(first).applied)
        assertEquals(CommandStatus.INVALID, store.execute(different).status)
        assertEquals("a, notes=b", store.entries.first().single().draft.merchant)
        assertEquals("c", store.entries.first().single().draft.notes)
    }
    @Test fun invalidOperationIdsReturnValidationResult() = runBlocking {
        assertEquals(CommandStatus.INVALID, store.execute(LedgerCommand.Save(draft, operationId = " ")).status)
        assertEquals(CommandStatus.INVALID, store.execute(LedgerCommand.Save(draft, operationId = "a".repeat(201))).status)
        assertTrue(store.entries.first().isEmpty())
    }
}
