package com.fishese.moneyfly.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.fishese.moneyfly.capture.*
import com.fishese.moneyfly.ledger.*
import com.fishese.moneyfly.recognition.*
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class NotificationInboxTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unsupportedNotificationRecordsMerchantNotesAndFallbackTimeWithoutTemplate() {
        val store = FakeInboxStore()
        val controller = FakeController()
        compose.setContent { MoneyFlyApp(store, controller) }
        compose.onNodeWithTag("destination_1", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("inbox_list").performScrollToNode(hasTestTag("record_receipt"))
        compose.onNodeWithTag("record_receipt", useUnmergedTree = true).performClick()
        compose.onNode(hasText("Synthetic notification title") and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertExists()
        compose.onNode(hasText("Synthetic unsupported body") and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("entry_amount").performScrollTo().performTextInput("42.50")
        compose.onNodeWithTag("entry_merchant").performScrollTo().performTextInput("茶餐廳")
        compose.onNodeWithTag("entry_notes").performScrollTo().performTextInput("Synthetic lunch note")
        compose.onNodeWithTag("save_entry", useUnmergedTree = true).performClick()
        compose.waitUntil { store.commands.any { it is LedgerCommand.SaveCaptured } }
        compose.runOnIdle {
            val command = store.commands.filterIsInstance<LedgerCommand.SaveCaptured>().single()
            assertEquals("receipt", command.receiptId)
            assertEquals(3L, command.expectedRevision)
            assertEquals("茶餐廳", command.draft.merchant)
            assertEquals("Synthetic lunch note", command.draft.notes)
            assertEquals(store.postedAt, command.draft.occurredAt)
            assertEquals(TimeOrigin.NOTIFICATION_POSTED, command.draft.timeOrigin)
            assertEquals(TimePrecision.UNKNOWN, command.draft.timePrecision)
            assertNull(command.draft.accountId)
            assertNull(command.draft.categoryId)
            assertEquals(SaveDecision.NORMAL, command.decision)
        }
    }

    @Test fun consentIsExplicitAndCommittedBeforeOpeningAndroidSettings() {
        val store = FakeInboxStore()
        val controller = FakeController { assertTrue(store.captureSettings.value.consent) }
        compose.setContent { MoneyFlyApp(store, controller) }
        compose.onNodeWithTag("destination_1", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("enable_capture", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(0, controller.settingsOpenCount); assertFalse(store.captureSettings.value.consent) }
        compose.onNodeWithTag("confirm_capture_consent", useUnmergedTree = true).performClick()
        compose.waitUntil { controller.settingsOpenCount == 1 }
        compose.runOnIdle { assertTrue(store.captureSettings.value.consent) }
    }

    @Test fun ignoredAppCanBeRestoredFromSettingsWithoutChangingLedger() {
        val store = FakeInboxStore()
        val controller = FakeController()
        compose.setContent { MoneyFlyApp(store, controller) }
        compose.onNodeWithTag("destination_1", useUnmergedTree = true).performClick()
        openReceiptActions()
        compose.onNodeWithTag("inbox_list").performScrollToNode(hasTestTag("ignore_app_receipt"))
        compose.onNodeWithTag("ignore_app_receipt", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("confirm_ignore_app", useUnmergedTree = true).performClick()
        compose.waitUntil { store.sources.value.single().ignored }
        compose.onNodeWithTag("destination_2", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("source_settings"))
        compose.onNodeWithTag("source_settings", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("restore_source_sample.app"))
        compose.onNodeWithTag("restore_source_sample.app", useUnmergedTree = true).performClick()
        compose.waitUntil { !store.sources.value.single().ignored }
        compose.runOnIdle { assertTrue(store.entries.value.isEmpty()); assertEquals(2, store.commands.filterIsInstance<LedgerCommand.SetIgnoredSource>().size) }
    }

    @Test fun swipeLeftOffersScopedIgnoreChoicesBeforeChangingAnything() {
        val store = FakeInboxStore()
        compose.setContent { MoneyFlyApp(store, FakeController()) }
        compose.onNodeWithTag("destination_1", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("inbox_list").performScrollToNode(hasTestTag("swipe_receipt"))
        compose.onNodeWithTag("swipe_receipt", useUnmergedTree = true).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(store.commands.any { it is LedgerCommand.IgnoreReceipt }) }
        compose.onNodeWithTag("swipe_ignore_this_receipt", useUnmergedTree = true).performClick()
        compose.waitUntil { store.commands.any { it is LedgerCommand.IgnoreReceipt } }
        compose.onNodeWithText("Undo", useUnmergedTree = true).performClick()
        compose.waitUntil { store.commands.any { it is LedgerCommand.ReopenReceipt } }
        compose.runOnIdle {
            val ignored = store.commands.filterIsInstance<LedgerCommand.IgnoreReceipt>().single()
            val restored = store.commands.filterIsInstance<LedgerCommand.ReopenReceipt>().single()
            assertEquals("receipt", ignored.receiptId)
            assertEquals(3L, ignored.expectedRevision)
            assertEquals(4L, restored.expectedRevision)
            assertEquals(InboxStatus.UNRECOGNIZED, store.inbox.value.single().status)
            assertTrue(store.entries.value.isEmpty())
            assertFalse(store.sources.value.single().ignored)
        }
    }

    @Test fun summaryIgnoreIsAvailableWithoutPatternAndDefaultsToSameApp() {
        val store = FakeInboxStore()
        store.inbox.value = store.inbox.value.map { it.copy(payload = it.payload!!.copy(groupSummary = true)) }
        compose.setContent { MoneyFlyApp(store, FakeController()) }
        compose.onNodeWithTag("destination_1", useUnmergedTree = true).performClick()
        openReceiptActions()
        compose.onNodeWithTag("inbox_list").performScrollToNode(hasTestTag("similar_receipt"))
        compose.onNodeWithTag("similar_receipt", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("ignore_rule_summary_only", useUnmergedTree = true).assertIsOn()
        compose.onNodeWithTag("ignore_rule_same_app", useUnmergedTree = true).assertIsOn()
        compose.onNodeWithTag("ignore_rule_pattern", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("save_ignore_rule", useUnmergedTree = true).assertIsNotEnabled()
        compose.onNodeWithTag("preview_ignore_rule", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("save_ignore_rule", useUnmergedTree = true).performClick()
        compose.waitUntil { store.commands.any { it is LedgerCommand.PutIgnoreRule } }
        compose.runOnIdle {
            val rule = store.commands.filterIsInstance<LedgerCommand.PutIgnoreRule>().single().rule
            assertTrue(rule.summaryOnly); assertTrue(rule.sameAppOnly); assertEquals("", rule.pattern)
        }
    }

    @Test fun similarRulePrefillsExactTitleAndRequiresReviewBeforeSavingEditedPattern() {
        val store = FakeInboxStore()
        compose.setContent { MoneyFlyApp(store, FakeController()) }
        compose.onNodeWithTag("destination_1", useUnmergedTree = true).performClick()
        openReceiptActions()
        compose.onNodeWithTag("inbox_list").performScrollToNode(hasTestTag("similar_receipt"))
        compose.onNodeWithTag("similar_receipt", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("ignore_rule_pattern", useUnmergedTree = true).assertTextEquals("Synthetic notification title")
        compose.onNodeWithTag("ignore_rule_same_app", useUnmergedTree = true).assertIsOn().performClick()
        compose.onNodeWithTag("ignore_rule_current_message", useUnmergedTree = true).assertTextEquals("Synthetic notification title")
        compose.onNodeWithTag("ignore_rule_name").performTextInput("Synthetic system state")
        compose.onNodeWithTag("ignore_rule_pattern").performTextReplacement("Synthetic * title")
        compose.onNodeWithTag("save_ignore_rule", useUnmergedTree = true).assertIsNotEnabled()
        compose.onNodeWithTag("preview_ignore_rule", useUnmergedTree = true).performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("save_ignore_rule", useUnmergedTree = true).assertIsEnabled().performClick()
        compose.waitUntil { store.commands.any { it is LedgerCommand.PutIgnoreRule } }
        compose.runOnIdle {
            val rule = store.commands.filterIsInstance<LedgerCommand.PutIgnoreRule>().single().rule
            assertEquals("Synthetic * title", rule.pattern)
            assertNotEquals(store.inbox.value.single().payload!!.title, rule.pattern)
            assertEquals("sample.app", rule.sourcePackage)
            assertFalse(rule.sameAppOnly)
            assertTrue(store.entries.value.isEmpty())
        }
    }

    @Test fun unknownIgnoreRuleSaveRetriesTheExactFrozenCommand() {
        val store = FakeInboxStore().apply { failNextIgnoreRuleSave = true }
        compose.setContent { MoneyFlyApp(store, FakeController()) }
        compose.onNodeWithTag("destination_1", useUnmergedTree = true).performClick()
        openReceiptActions()
        compose.onNodeWithTag("inbox_list").performScrollToNode(hasTestTag("similar_receipt"))
        compose.onNodeWithTag("similar_receipt", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("ignore_rule_name").performTextInput("Synthetic state")
        compose.onNodeWithTag("ignore_rule_pattern").performTextReplacement("Synthetic * title")
        compose.onNodeWithTag("preview_ignore_rule", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("save_ignore_rule", useUnmergedTree = true).performClick()
        compose.waitUntil { store.commands.filterIsInstance<LedgerCommand.PutIgnoreRule>().size == 1 }
        compose.onNodeWithTag("ignore_rule_save_error", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("ignore_rule_pattern", useUnmergedTree = true).assertIsNotEnabled()
        compose.onNodeWithTag("save_ignore_rule", useUnmergedTree = true).performClick()
        compose.waitUntil { store.commands.filterIsInstance<LedgerCommand.PutIgnoreRule>().size == 2 }
        compose.runOnIdle {
            val attempts = store.commands.filterIsInstance<LedgerCommand.PutIgnoreRule>()
            assertEquals(attempts[0], attempts[1])
            assertEquals(1, store.ignoreRules.value.size)
        }
    }

    @Test fun relatedNotificationLinksOnlyAfterExplicitConfirmation() {
        val store = FakeInboxStore()
        store.entries.value = listOf(LedgerEntry("existing", 7, EntryDraft(amount = "42.50", merchant = "Synthetic shop", occurredAt = store.postedAt), createdAt = store.postedAt, updatedAt = store.postedAt, lastOperationId = "synthetic-save"))
        store.inbox.value = store.inbox.value.map { it.copy(status = InboxStatus.REVIEW_NEEDED, relatedEntryIds = listOf("existing"), requiresRevisionDecision = true) }
        compose.setContent { MoneyFlyApp(store, FakeController()) }
        compose.onNodeWithTag("destination_1", useUnmergedTree = true).performClick()
        openReceiptActions()
        compose.onNodeWithTag("inbox_list").performScrollToNode(hasTestTag("link_receipt"))
        compose.onNodeWithTag("link_receipt", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertTrue(store.commands.isEmpty()) }
        compose.onNodeWithTag("inbox_list").performScrollToNode(hasTestTag("confirm_link_receipt"))
        compose.onNodeWithTag("confirm_link_receipt", useUnmergedTree = true).performClick()
        compose.waitUntil { store.commands.any { it is LedgerCommand.LinkDuplicate } }
        compose.runOnIdle {
            val command = store.commands.single() as LedgerCommand.LinkDuplicate
            assertEquals("existing", command.targetEntryId)
            assertEquals(7L, command.expectedEntryRevision)
            assertEquals(3L, command.expectedRevision)
            assertEquals(1, store.entries.value.size)
        }
    }

    @Test fun newEpisodeFromAnotherSourceCanExplicitlyLinkAnExistingManualEntry() {
        val store = FakeInboxStore()
        store.entries.value = listOf(LedgerEntry("manual", 8, EntryDraft(amount = "42.50", merchant = "Synthetic manual purchase", occurredAt = store.postedAt, sourcePackage = null), createdAt = store.postedAt, updatedAt = store.postedAt, lastOperationId = "manual-save"))
        // The retained receipt has no episode relationship or parser evidence for this entry.
        assertTrue(store.inbox.value.single().relatedEntryIds.isEmpty())
        compose.setContent { MoneyFlyApp(store, FakeController()) }
        compose.onNodeWithTag("destination_1", useUnmergedTree = true).performClick()
        openReceiptActions()
        compose.onNodeWithTag("inbox_list").performScrollToNode(hasTestTag("link_receipt"))
        compose.onNodeWithTag("link_receipt", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertTrue(store.commands.isEmpty()) }
        compose.onNodeWithTag("inbox_list").performScrollToNode(hasTestTag("confirm_link_receipt"))
        compose.onNodeWithTag("confirm_link_receipt", useUnmergedTree = true).assertIsEnabled().performClick()
        compose.waitUntil { store.commands.any { it is LedgerCommand.LinkDuplicate } }
        compose.runOnIdle {
            val command = store.commands.single() as LedgerCommand.LinkDuplicate
            assertEquals("manual", command.targetEntryId)
            assertEquals(8L, command.expectedEntryRevision)
            assertEquals(3L, command.expectedRevision)
            assertEquals(1, store.entries.value.size)
            assertEquals(InboxStatus.LINKED_DUPLICATE, store.inbox.value.single().status)
        }
    }

    @Test fun invalidTemplateNeverBlocksRecordingTheNotification() {
        val store = FakeInboxStore()
        compose.setContent { MoneyFlyApp(store, FakeController()) }
        compose.onNodeWithTag("destination_1", useUnmergedTree = true).performClick()
        openReceiptActions()
        compose.onNodeWithTag("inbox_list").performScrollToNode(hasTestTag("template_receipt"))
        compose.onNodeWithTag("template_receipt", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("preview_template", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("template_error", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("save_template", useUnmergedTree = true).assertIsNotEnabled()
        compose.onNodeWithTag("builder_record_entry", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("entry_amount").performScrollTo().performTextInput("11.00")
        compose.onNodeWithTag("save_entry", useUnmergedTree = true).performClick()
        compose.waitUntil { store.commands.any { it is LedgerCommand.SaveCaptured } }
        compose.runOnIdle { assertFalse(store.commands.any { it is LedgerCommand.PutTemplate }); assertEquals(1, store.entries.value.size) }
    }

    @Test fun builderPreviewsChangedSyntheticPurchasesAndEnablesOneTemplate() {
        val store = FakeInboxStore()
        store.inbox.value = store.inbox.value.map { it.copy(payload = it.payload!!.copy(title = "Synthetic payment", body = "Paid HKD 42.50 at Synthetic Shop.")) }
        compose.setContent { MoneyFlyApp(store, FakeController()) }
        compose.onNodeWithTag("destination_1", useUnmergedTree = true).performClick()
        openReceiptActions()
        compose.onNodeWithTag("inbox_list").performScrollToNode(hasTestTag("template_receipt"))
        compose.onNodeWithTag("template_receipt", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("template_name").performScrollTo().performTextInput("Synthetic purchase format")
        compose.onNodeWithTag("template_amount").performScrollTo().performTextInput("42.50")
        compose.onNodeWithTag("template_merchant").performScrollTo().performTextInput("Synthetic Shop")
        compose.onNodeWithTag("confirm_template_currency", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("preview_template", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("save_template", useUnmergedTree = true).assertIsEnabled().performClick()
        compose.waitUntil { store.commands.any { it is LedgerCommand.PutTemplate } }
        compose.runOnIdle {
            val command = store.commands.single() as LedgerCommand.PutTemplate
            assertTrue(command.definition.enabled)
            assertEquals("sample.app", command.definition.sourcePackage)
            assertEquals(FixedCurrencyEvidence.USER_CONFIRMED_ASSUMPTION, command.definition.fixedCurrencyEvidence)
            assertEquals(setOf(SlotName.AMOUNT, SlotName.MERCHANT), command.definition.patterns.flatMap { it.segments }.filterIsInstance<PatternSegment.Slot>().map { it.name }.toSet())
            assertTrue(store.entries.value.isEmpty())
        }
    }

    @Test fun readyDraftSavesOnceWithoutRequiringEditorOrTemplateCreation() {
        val store = FakeInboxStore()
        val draft = EntryDraft(amount = "42.50", merchant = "Synthetic shop", sourcePackage = "sample.app", occurredAt = store.postedAt, timeOrigin = TimeOrigin.NOTIFICATION_POSTED, timePrecision = TimePrecision.UNKNOWN)
        store.inbox.value = store.inbox.value.map { it.copy(status = InboxStatus.READY, draft = draft, matchTemplateId = "synthetic-template", matchTemplateRevision = 2) }
        compose.setContent { MoneyFlyApp(store, FakeController()) }
        compose.onNodeWithTag("destination_1", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("inbox_list").performScrollToNode(hasTestTag("save_ready_receipt"))
        compose.onNodeWithTag("save_ready_receipt", useUnmergedTree = true).performClick()
        compose.waitUntil { store.commands.any { it is LedgerCommand.SaveCaptured } }
        compose.runOnIdle {
            val command = store.commands.single() as LedgerCommand.SaveCaptured
            assertEquals(draft, command.draft)
            assertEquals(1, store.entries.value.size)
        }
    }

    @Test fun merchantSearchAndReadyFilterFindRecognizedDraftWithoutRawTextMatch() {
        val store = FakeInboxStore()
        val original = store.inbox.value.single()
        store.inbox.value = listOf(
            original.copy(status = InboxStatus.READY, draft = EntryDraft(amount = "18.60", merchant = "Synthetic Orchard", occurredAt = store.postedAt)),
            original.copy(id = "other", sourcePackage = "other.app"),
        )
        compose.setContent { MoneyFlyApp(store, FakeController()) }
        compose.onNodeWithTag("destination_1", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("inbox_filter_ready").performClick()
        compose.onNodeWithTag("inbox_search").performTextInput("orchard")
        compose.onNodeWithTag("inbox_list").performScrollToNode(hasTestTag("save_ready_receipt"))
        compose.onNodeWithText("Synthetic Orchard", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("receipt_other").assertDoesNotExist()
        compose.runOnIdle { assertTrue(store.commands.isEmpty()); assertTrue(store.entries.value.isEmpty()) }
    }

    @Test fun moreActionsProvideExplicitIgnoreAlternativeWithoutChangingLedger() {
        val store = FakeInboxStore()
        compose.setContent { MoneyFlyApp(store, FakeController()) }
        compose.onNodeWithTag("destination_1", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("inbox_list").performScrollToNode(hasTestTag("record_receipt"))
        compose.onNodeWithTag("ignore_receipt").assertDoesNotExist()
        openReceiptActions()
        compose.onNodeWithTag("inbox_list").performScrollToNode(hasTestTag("ignore_receipt"))
        compose.onNodeWithTag("ignore_receipt", useUnmergedTree = true).performClick()
        compose.waitUntil { store.commands.any { it is LedgerCommand.IgnoreReceipt } }
        compose.runOnIdle {
            assertEquals(3L, store.commands.filterIsInstance<LedgerCommand.IgnoreReceipt>().single().expectedRevision)
            assertTrue(store.entries.value.isEmpty())
            assertFalse(store.sources.value.single().ignored)
        }
    }

    @Test fun appIgnoredReceiptRestoresSourceWithoutReopeningIndividualReceiptOrWritingLedger() {
        val store = FakeInboxStore()
        store.sources.value = listOf(SourcePreference("sample.app", ignored = true, revision = 6))
        store.inbox.value = store.inbox.value.map { it.copy(status = InboxStatus.IGNORED) }
        compose.setContent { MoneyFlyApp(store, FakeController()) }
        compose.onNodeWithTag("destination_1", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("inbox_filter_ignored", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("inbox_list").performScrollToNode(hasTestTag("restore_app_receipt"))
        compose.onNodeWithTag("reopen_receipt").assertDoesNotExist()
        compose.onNodeWithTag("restore_app_receipt", useUnmergedTree = true).performClick()
        compose.waitUntil { store.commands.any { it is LedgerCommand.SetIgnoredSource } }
        compose.runOnIdle {
            val command = store.commands.single() as LedgerCommand.SetIgnoredSource
            assertEquals("sample.app", command.sourcePackage)
            assertFalse(command.ignored)
            assertEquals(6L, command.expectedRevision)
            assertTrue(store.entries.value.isEmpty())
            assertFalse(store.sources.value.single().ignored)
        }
    }

    private fun openReceiptActions() {
        compose.onNodeWithTag("inbox_list").performScrollToNode(hasTestTag("more_receipt"))
        compose.onNodeWithTag("more_receipt", useUnmergedTree = true).performClick()
    }

    private class FakeController(val beforeOpen: () -> Unit = {}) : CaptureController {
        override val connection = MutableStateFlow(CaptureConnection())
        var settingsOpenCount = 0
        override fun onForeground() = Unit
        override fun openAccessSettings() { beforeOpen(); settingsOpenCount++ }
        override fun requestReconnect() = Unit
        override suspend fun refresh(): Boolean = true
    }

    private class FakeInboxStore : LedgerStore, InboxStore, TemplateStore {
        val postedAt = Instant.parse("2026-10-24T03:22:41.123456789Z")
        override val entries = MutableStateFlow<List<LedgerEntry>>(emptyList())
        override val categories = MutableStateFlow<List<Category>>(emptyList())
        override val accounts = MutableStateFlow<List<AccountLabel>>(emptyList())
        override val aliases = MutableStateFlow<List<MerchantAlias>>(emptyList())
        override val rules = MutableStateFlow<List<CategoryRule>>(emptyList())
        override val templates = MutableStateFlow<List<TemplateDefinition>>(emptyList())
        override val ignoreRules = MutableStateFlow<List<IgnoreRule>>(emptyList())
        override val sources = MutableStateFlow(listOf(SourcePreference("sample.app", revision = 1)))
        override val captureSettings = MutableStateFlow(CaptureSettings())
        override val inbox = MutableStateFlow(listOf(InboxItem("receipt", 3, "sample.app", postedAt, postedAt, 1, InboxStatus.UNRECOGNIZED,
            NotificationPayload("sample.app", "synthetic", "synthetic-key", postedAt, postedAt, title = "Synthetic notification title", body = "Synthetic unsupported body"))))
        val commands = mutableListOf<LedgerCommand>()
        var failNextIgnoreRuleSave = false
        override suspend fun execute(command: LedgerCommand): CommandResult {
            commands += command
            if (command is LedgerCommand.PutIgnoreRule && failNextIgnoreRuleSave) {
                failNextIgnoreRuleSave = false
                throw IllegalStateException("Synthetic unknown result")
            }
            when (command) {
                is LedgerCommand.SetCaptureConsent -> captureSettings.value = CaptureSettings(command.enabled, command.expectedRevision + 1)
                is LedgerCommand.SetIgnoredSource -> {
                    sources.value = listOf(SourcePreference(command.sourcePackage, command.ignored, revision = command.expectedRevision + 1))
                    inbox.value = inbox.value.map { it.copy(status = if (command.ignored) InboxStatus.IGNORED else InboxStatus.UNRECOGNIZED, revision = it.revision + 1) }
                }
                is LedgerCommand.SaveCaptured -> {
                    val entry = LedgerEntry(command.entryId, 1, command.draft, createdAt = postedAt, updatedAt = postedAt, lastOperationId = command.operationId)
                    entries.value = listOf(entry)
                    inbox.value = inbox.value.map { it.copy(status = InboxStatus.SAVED, entryId = entry.id, revision = it.revision + 1) }
                }
                is LedgerCommand.IgnoreReceipt -> inbox.value = inbox.value.map { it.copy(status = InboxStatus.IGNORED, revision = it.revision + 1) }
                is LedgerCommand.ReopenReceipt -> inbox.value = inbox.value.map { it.copy(status = InboxStatus.UNRECOGNIZED, revision = it.revision + 1) }
                is LedgerCommand.LinkDuplicate -> inbox.value = inbox.value.map { it.copy(status = InboxStatus.LINKED_DUPLICATE, entryId = command.targetEntryId, revision = it.revision + 1) }
                is LedgerCommand.PutTemplate -> templates.value = listOf(command.definition.copy(revision = 1))
                is LedgerCommand.PutIgnoreRule -> ignoreRules.value = listOf(command.rule.copy(revision = 1))
                else -> error("Unsupported synthetic test command")
            }
            return CommandResult(CommandStatus.APPLIED, revision = 4)
        }
    }
}
