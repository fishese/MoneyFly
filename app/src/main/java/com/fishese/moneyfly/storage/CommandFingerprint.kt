package com.fishese.moneyfly.storage

import com.fishese.moneyfly.ledger.EntryDraft
import com.fishese.moneyfly.ledger.LedgerCommand
import com.fishese.moneyfly.recognition.PatternSegment
import java.security.MessageDigest

/** Length-prefixed nullable fields keep user text from impersonating field separators. */
internal object CommandFingerprint {
    fun canonical(command: LedgerCommand): String {
        val fields = buildList<Any?> {
            add("ledger-command-v1")
            add(command.operationId)
            fun draft(value: EntryDraft) {
                add(value.amount); add(value.currency); add(value.kind.name); add(value.occurredAt.toString())
                add(value.merchant); add(value.notes); add(value.categoryId); add(value.accountId)
                add(value.canonicalMerchant); add(value.sourcePackage); add(value.timeOrigin.name)
                add(value.timePrecision.name); add(value.categoryOrigin.name)
                if (value.exchangeRate != null || value.exchangeRateCurrency != null) {
                    add("entry-exchange-rate"); add(value.exchangeRate); add(value.exchangeRateCurrency)
                }
                if (value.suppressAccountRule) add("suppress-account-rule")
            }
            when (command) {
                is LedgerCommand.Save -> { add("save"); add(command.entryId); draft(command.draft) }
                is LedgerCommand.Edit -> { add("edit"); add(command.entryId); add(command.expectedRevision); draft(command.draft) }
                is LedgerCommand.Delete -> { add("delete"); add(command.entryId); add(command.expectedRevision) }
                is LedgerCommand.Undo -> { add("undo"); add(command.targetOperationId); add(command.expectedRevision) }
                is LedgerCommand.PutCategory -> { add("put-category"); add(command.id); add(command.name); add(command.expectedRevision) }
                is LedgerCommand.DeleteCategory -> { add("delete-category"); add(command.id); add(command.expectedRevision) }
                is LedgerCommand.PutAccount -> { add("put-account"); add(command.id); add(command.name); add(command.kind.name); add(command.expectedRevision); if (command.icon != null) { add("account-icon"); add(command.icon) } }
                is LedgerCommand.DeleteAccount -> { add("delete-account"); add(command.id); add(command.expectedRevision) }
                is LedgerCommand.PutAlias -> { add("put-alias"); add(command.id); add(command.spelling); add(command.canonicalName); add(command.expectedRevision) }
                is LedgerCommand.DeleteAlias -> { add("delete-alias"); add(command.id); add(command.expectedRevision) }
                is LedgerCommand.PutRule -> {
                    val r = command.rule
                    add("put-rule"); add(r.id); add(r.field.name); add(r.match.name); add(r.literal); add(r.categoryId)
                    add(r.priority); add(r.sourcePackage); add(r.accountId); add(r.revision); add(command.expectedRevision)
                }
                is LedgerCommand.DeleteRule -> { add("delete-rule"); add(command.id); add(command.expectedRevision) }
                is LedgerCommand.PutAccountRule -> { val r = command.rule; add("put-account-rule"); add(r.id); add(r.merchant); add(r.sourcePackage); add(r.accountId); add(r.revision); add(command.expectedRevision) }
                is LedgerCommand.DeleteAccountRule -> { add("delete-account-rule"); add(command.id); add(command.expectedRevision) }
                is LedgerCommand.CaptureNotification -> {
                    val p = command.payload
                    add("capture-notification"); add(p.sourcePackage); add(p.userScope); add(p.notificationKey)
                    add(p.postedAt); add(p.observedAt); add(p.title); add(p.body); add(p.bigText)
                    add(p.textLines.size); p.textLines.forEach { add(it) }; add(p.subText)
                    add(p.groupSummary); add(p.redacted); add(p.truncated)
                }
                is LedgerCommand.NotificationRemoved -> { add("notification-removed"); add(command.sourcePackage); add(command.userScope); add(command.notificationKey) }
                is LedgerCommand.SetCaptureConsent -> { add("capture-consent"); add(command.enabled); add(command.expectedRevision) }
                is LedgerCommand.SetIgnoredSource -> { add("ignored-source"); add(command.sourcePackage); add(command.ignored); add(command.expectedRevision) }
                is LedgerCommand.SetParsingSource -> { add("parsing-source"); add(command.sourcePackage); add(command.enabled); add(command.expectedRevision) }
                is LedgerCommand.SaveCaptured -> { add("save-captured"); add(command.receiptId); add(command.expectedRevision); draft(command.draft); add(command.decision.name); add(command.entryId) }
                is LedgerCommand.LinkDuplicate -> { add("link-duplicate"); add(command.receiptId); add(command.expectedRevision); add(command.targetEntryId); add(command.expectedEntryRevision) }
                is LedgerCommand.IgnoreReceipt -> { add("ignore-receipt"); add(command.receiptId); add(command.expectedRevision) }
                is LedgerCommand.ReopenReceipt -> { add("reopen-receipt"); add(command.receiptId); add(command.expectedRevision) }
                is LedgerCommand.PruneInbox -> add("prune-inbox")
                is LedgerCommand.PutTemplate -> {
                    val d = command.definition
                    add("put-template"); add(d.id); add(d.name); add(d.sourcePackage); add(d.patterns.size)
                    d.patterns.forEach { p -> add(p.field.name); add(p.segments.size); p.segments.forEach { s -> when (s) {
                        is PatternSegment.Literal -> { add("literal"); add(s.text) }
                        is PatternSegment.Slot -> { add("slot"); add(s.name.name); add(s.maxLength) }
                    } } }
                    add(d.predicates.size); d.predicates.forEach { p -> add(p.field.name); add(p.operation.name); add(p.literal); add(p.ignoreCase) }
                    add(d.kind.name); add(d.fixedCurrency); add(d.fixedCurrencyEvidence?.name); add(d.fixedMerchant)
                    add(d.currencyTokens.size); d.currencyTokens.forEach { t -> add(t.token); add(t.currency); add(t.userConfirmedAmbiguous) }
                    add(d.numberConvention.name); add(d.enabled); add(d.revision); add(command.expectedRevision)
                }
                is LedgerCommand.SetTemplateEnabled -> { add("set-template-enabled"); add(command.templateId); add(command.expectedRevision); add(command.enabled) }
                is LedgerCommand.DeleteTemplate -> { add("delete-template"); add(command.templateId); add(command.expectedRevision) }
                is LedgerCommand.RepreviewReceipt -> { add("repreview-receipt"); add(command.receiptId); add(command.expectedRevision) }
                is LedgerCommand.ApplyTemplateToPending -> { add("apply-template-to-pending"); add(command.templateId); add(command.expectedRevision) }
                is LedgerCommand.PutIgnoreRule -> {
                    val r = command.rule
                    add("put-ignore-rule"); add(r.id); add(r.name); add(r.sourcePackage); add(r.field.name)
                    add(r.pattern); add(r.ignoreCase); add(r.enabled); add(r.revision); add(command.expectedRevision)
                    // Preserve pre-v5 fingerprints for the default app-scoped behavior.
                    if (!r.sameAppOnly) { add("cross-app"); add(false) }
                    if (r.summaryOnly) { add("summary-only"); add(true) }
                }
                is LedgerCommand.SetIgnoreRuleEnabled -> { add("set-ignore-rule-enabled"); add(command.ruleId); add(command.expectedRevision); add(command.enabled) }
                is LedgerCommand.DeleteIgnoreRule -> { add("delete-ignore-rule"); add(command.ruleId); add(command.expectedRevision) }
            }
        }
        return buildString {
            for (field in fields) {
                if (field == null) append("N;") else {
                    val text = field.toString()
                    append('S'); append(text.length); append(':'); append(text); append(';')
                }
            }
        }
    }
    fun of(command: LedgerCommand): String {
        val encoded = canonical(command)
        // Hash UTF-16 code units directly so even unpaired surrogates cannot collapse
        // through a charset encoder's replacement-character behavior.
        val digest = MessageDigest.getInstance("SHA-256")
        for (char in encoded) {
            digest.update((char.code ushr 8).toByte())
            digest.update(char.code.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
