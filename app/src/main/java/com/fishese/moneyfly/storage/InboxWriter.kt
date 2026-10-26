package com.fishese.moneyfly.storage

import com.fishese.moneyfly.capture.*
import com.fishese.moneyfly.ledger.*
import com.fishese.moneyfly.recognition.*
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class RawRetention(val maxAge: Duration = Duration.ofDays(7), val maxItems: Int = 1000, val maxBytes: Long = 10L * 1024 * 1024) {
    init { require(!maxAge.isNegative && maxItems >= 0 && maxBytes >= 0) }
}

/** Invoked exclusively inside the ledger writer's transaction. */
internal class InboxWriter(private val dao: InboxDao, private val ledger: LedgerDao, private val digester: ReceiptDigester, private val retention: RawRetention, private val templates: TemplateWriter, private val ignoreRules: IgnoreRuleWriter) {
    suspend fun execute(command: LedgerCommand, now: Instant, validate: suspend (EntryDraft) -> EntryDraft): CommandResult = when (command) {
        is LedgerCommand.CaptureNotification -> capture(command, now, validate)
        is LedgerCommand.NotificationRemoved -> {
            val key = slotKey(command.sourcePackage, command.userScope, command.notificationKey)
            dao.slot(key)?.let { dao.putSlot(it.copy(active = false)); dao.deleteExpiredRevisions(it.episodeId) }
            ok()
        }
        is LedgerCommand.SetCaptureConsent -> {
            val old = dao.settingsOnce()
            if ((old?.revision ?: 0) != command.expectedRevision) conflict("Capture settings changed.", revision = old?.revision ?: 0)
            else { val revision = (old?.revision ?: 0) + 1; dao.putSettings(CaptureSettingsRow(consent = command.enabled, revision = revision)); ok(revision = revision) }
        }
        is LedgerCommand.SetIgnoredSource -> sourceSetting(command.sourcePackage, command.expectedRevision, command.ignored, null)
        is LedgerCommand.SetParsingSource -> sourceSetting(command.sourcePackage, command.expectedRevision, null, command.enabled)
        is LedgerCommand.SaveCaptured -> {
            val receipt = dao.receipt(command.receiptId)
            val problem = pendingProblem(receipt, command.expectedRevision)
            if (problem != null) problem else {
                receipt!!
                val siblings = dao.episodeReceipts(receipt.episodeId)
                if ((receipt.contentRevision > 1 || siblings.any { it.entryId != null }) && command.decision != SaveDecision.SEPARATE_PURCHASE)
                    conflict("This notification changed. Choose a separate purchase or link the existing entry.", receipt.id, receipt.revision)
                else {
                    require(command.entryId.isNotBlank() && command.entryId.length <= 200) { "A valid entry ID is required." }
                    require(ledger.entry(command.entryId) == null) { "This entry ID already exists." }
                    val draft = validate(command.draft).copy(sourcePackage = receipt.sourcePackage)
                    val entry = LedgerEntry(command.entryId, 1, draft, false, now, now, command.operationId)
                    ledger.putEntry(entry.toRow())
                    dao.putReceipt(receipt.copy(revision = receipt.revision + 1, settlement = "SAVED", entryId = entry.id))
                    CommandResult(CommandStatus.APPLIED, entry.id, entry.revision, entry)
                }
            }
        }
        is LedgerCommand.LinkDuplicate -> {
            val receipt = dao.receipt(command.receiptId)
            val problem = pendingProblem(receipt, command.expectedRevision)
            if (problem != null) problem else {
                val entry = ledger.entry(command.targetEntryId)?.toEntry()
                if (entry == null || entry.deleted || entry.revision != command.expectedEntryRevision)
                    CommandResult(CommandStatus.CONFLICT, entry?.id, entry?.revision, entry, "The selected entry changed or was removed.")
                else {
                    dao.putReceipt(receipt!!.copy(revision = receipt.revision + 1, settlement = "LINKED_DUPLICATE", entryId = entry.id))
                    CommandResult(CommandStatus.APPLIED, entry.id, entry.revision, entry)
                }
            }
        }
        is LedgerCommand.IgnoreReceipt -> {
            val row = dao.receipt(command.receiptId)
            when {
                row == null -> missing()
                row.revision != command.expectedRevision || row.settlement != null -> conflict("This Inbox item changed.", row.id, row.revision)
                else -> { dao.putReceipt(row.copy(revision = row.revision + 1, settlement = "IGNORED")); ok(row.id, row.revision + 1) }
            }
        }
        is LedgerCommand.ReopenReceipt -> {
            val row = dao.receipt(command.receiptId)
            when {
                row == null -> missing()
                row.revision != command.expectedRevision || row.settlement != "IGNORED" -> conflict("This Inbox item changed.", row.id, row.revision)
                dao.rawOnce(row.id) == null -> invalid("The retained content expired and cannot be reopened.")
                dao.source(row.sourcePackage)?.ignored == true -> invalid("Restore this app in ignored apps first.")
                else -> { dao.putReceipt(row.copy(revision = row.revision + 1, settlement = null)); ok(row.id, row.revision + 1) }
            }
        }
        is LedgerCommand.PruneInbox -> { prune(now); ok() }
        else -> error("Unsupported Inbox command")
    }

    private suspend fun capture(command: LedgerCommand.CaptureNotification, now: Instant, validate: suspend (EntryDraft) -> EntryDraft): CommandResult {
        val p = command.payload
        require(p.sourcePackage.isNotBlank() && p.sourcePackage.length <= 300) { "A source package is required." }
        require(p.userScope.isNotBlank() && p.userScope.length <= 500 && p.notificationKey.isNotBlank() && p.notificationKey.length <= 2000) { "Invalid notification identity." }
        if (dao.settingsOnce()?.consent != true) return invalid("Enable Inbox capture before retaining notifications.")
        ignoreRules.ensureDefaults(p.sourcePackage)
        val source = dao.source(p.sourcePackage)
        if (source?.ignored == true) return ok()
        require(p.textLines.size <= 100 && listOf(p.title, p.body, p.bigText, p.subText).all { it.length <= 200000 } && p.textLines.all { it.length <= 200000 }) { "Notification content exceeds capture limits." }
        val raw = InboxRawCodec.encode(p)
        val bytes = raw.toByteArray(Charsets.UTF_8).size.toLong()
        require(bytes <= 1024 * 1024) { "Notification content exceeds capture limits." }
        for (time in listOf(p.postedAt, p.observedAt)) {
            val year = try { time.atZone(java.time.ZoneOffset.UTC).year } catch (_: java.time.DateTimeException) { 0 }
            require(year in 1..9999) { "Notification date is outside the supported range." }
        }
        val slotKey = slotKey(p.sourcePackage, p.userScope, p.notificationKey)
        val slot = dao.slot(slotKey)
        val episode = if (slot?.active == true) slot.episodeId else digester.digest(pack("episode-v1", slotKey, UUID.randomUUID().toString()))
        val fingerprint = digester.digest(pack("receipt-content-v1", p.postedAt.toString(), FinancialIdentity.canonicalText(p.title),
            FinancialIdentity.canonicalText(p.body), FinancialIdentity.canonicalText(p.bigText), FinancialIdentity.canonicalText(p.subText),
            p.groupSummary.toString(), p.redacted.toString(), p.truncated.toString(), p.textLines.size.toString(), *p.textLines.map { FinancialIdentity.canonicalText(it) }.toTypedArray()))
        val siblings = dao.episodeReceipts(episode)
        val expired = dao.expiredRevisions(episode)
        if (expired.any { it.fingerprint == fingerprint }) return ok()
        val existing = siblings.firstOrNull { it.fingerprint == fingerprint }
        if (existing != null) { prune(now); return ok(existing.id, existing.revision) }
        val row = ReceiptRow(UUID.randomUUID().toString(), episode, fingerprint, p.sourcePackage, p.postedAt.toString(), p.observedAt.toString(),
            maxOf(siblings.maxOfOrNull { it.contentRevision } ?: 0, expired.maxOfOrNull { it.contentRevision } ?: 0) + 1, 1, null, null, false)
        if (source == null) dao.putSource(SourceRow(p.sourcePackage, false, false, 1))
        dao.putSlot(NotificationSlotRow(slotKey, episode, true))
        dao.putReceipt(row); dao.putRaw(RawRow(row.id, raw, now.toEpochMilli(), bytes))
        val recognized = templates.freeze(row, p)
        recordIfClear(row, p, recognized, now, command.operationId, validate)
        prune(now)
        return ok(row.id, dao.receipt(row.id)?.revision ?: row.revision)
    }

    /** Template enrollment and this backfill share the same ledger transaction. */
    suspend fun applyTemplateToPending(sourcePackage: String, now: Instant, operationId: String, validate: suspend (EntryDraft) -> EntryDraft) {
        if (dao.source(sourcePackage)?.ignored == true) return
        val compiled = ignoreRules.currentCompiled()
        val episodeReceipts = dao.receipts().first()
        val pending = episodeReceipts.filter { it.sourcePackage == sourcePackage && it.settlement == null && !it.removed }
            .sortedWith(compareBy<ReceiptRow> { it.postedAt }.thenBy { it.id })
        val latestRevisionByEpisode = episodeReceipts.groupBy { it.episodeId }.mapValues { (_, rows) -> rows.maxOf { it.contentRevision } }
        for (row in pending) {
            val raw = dao.rawOnce(row.id) ?: continue
            val payload = InboxRawCodec.decode(raw.payload, row)
            if (IgnoreRuleMatcher.matchingRule(payload, compiled) != null) continue
            val recognized = templates.freeze(row, payload)
            // Invalidate any old editor/review snapshot even if this match remains pending.
            val revised = row.copy(revision = row.revision + 1)
            dao.putReceipt(revised)
            if (row.contentRevision < latestRevisionByEpisode[row.episodeId]!!) {
                templates.requireReview(revised, recognized, "A newer notification update exists. Review the latest version before recording.")
            } else {
                recordIfClear(revised, payload, recognized, now, operationId, validate)
            }
        }
    }

    private suspend fun recordIfClear(row: ReceiptRow, p: NotificationPayload, recognized: RecognitionResult,
        now: Instant, operationId: String, validate: suspend (EntryDraft) -> EntryDraft) {
        val ignored = IgnoreRuleMatcher.matchingRule(p, ignoreRules.currentCompiled()) != null
        if (!ignored && recognized.status == RecognitionStatus.READY && recognized.draft != null) {
            val draft = try { validate(recognized.draft.copy(sourcePackage = p.sourcePackage)) } catch (_: IllegalArgumentException) { null }
            if (draft == null) templates.requireReview(row, recognized, "The matched entry needs review before saving.")
            else {
                val entries = ledger.entries().first().map { it.toEntry() }
                val possibleDuplicate = possibleDuplicateEntries(draft, entries).isNotEmpty() ||
                    entries.any { other -> other.draft.currency == draft.currency && other.draft.kind == draft.kind &&
                        java.math.BigDecimal(other.draft.amount).compareTo(java.math.BigDecimal(draft.amount)) == 0 &&
                        (draft.merchant.isBlank() || other.draft.merchant.isBlank()) &&
                        Duration.between(other.draft.occurredAt, draft.occurredAt).abs() <= Duration.ofDays(2) }
                if (possibleDuplicate) templates.requireReview(row, recognized, "A possible duplicate exists. Review before saving another entry.")
                else {
                    val entry = LedgerEntry("capture:${row.id}", 1, draft, false, now, now, operationId)
                    ledger.putEntry(entry.toRow())
                    dao.putReceipt(row.copy(revision = row.revision + 1, settlement = "SAVED", entryId = entry.id))
                }
            }
        }
    }

    suspend fun prune(now: Instant) {
        val rows = dao.allRaw()
        val cutoff = now.minus(retention.maxAge).toEpochMilli()
        var count = rows.size
        var bytes = rows.sumOf { it.byteCount }
        for (row in rows) {
            if (row.capturedAt <= cutoff || count > retention.maxItems || bytes > retention.maxBytes) {
                templates.clear(row.receiptId); dao.deleteRaw(row.receiptId); count--; bytes -= row.byteCount
                val receipt = dao.receipt(row.receiptId)
                if (receipt != null && receipt.settlement == null) {
                    if (dao.activeEpisodeCount(receipt.episodeId) > 0)
                        dao.putExpiredRevision(ExpiredRevisionRow(receipt.episodeId, receipt.fingerprint, receipt.contentRevision))
                    dao.deleteReceipt(receipt.id)
                }
            }
        }
    }
    private suspend fun sourceSetting(source: String, expected: Long, ignored: Boolean?, parsing: Boolean?): CommandResult {
        require(source.isNotBlank() && source.length <= 300) { "A source package is required." }
        val old = dao.source(source) ?: SourceRow(source, false, false, 0)
        if (old.revision != expected) return conflict("This app preference changed.", source, old.revision)
        val updated = old.copy(ignored = ignored ?: old.ignored, parsingEnabled = parsing ?: old.parsingEnabled, revision = old.revision + 1)
        dao.putSource(updated); return ok(source, updated.revision)
    }
    private suspend fun pendingProblem(row: ReceiptRow?, expected: Long): CommandResult? = when {
        row == null -> missing()
        row.revision != expected || row.settlement != null -> conflict("This Inbox item changed or was already settled.", row.id, row.revision)
        dao.rawOnce(row.id) == null -> invalid("The retained content expired.")
        dao.source(row.sourcePackage)?.ignored == true -> invalid("Restore this app in ignored apps first.")
        else -> null
    }
    private fun slotKey(source: String, user: String, key: String) = digester.digest(pack("notification-slot-v1", source, user, key))
    private fun pack(vararg fields: String) = fields.joinToString("") { "${it.length}:$it;" }
    private fun ok(id: String? = null, revision: Long? = null) = CommandResult(CommandStatus.APPLIED, id, revision)
    private fun conflict(message: String, id: String? = null, revision: Long? = null) = CommandResult(CommandStatus.CONFLICT, id, revision, message = message)
    private fun invalid(message: String) = CommandResult(CommandStatus.INVALID, message = message)
    private fun missing() = CommandResult(CommandStatus.NOT_FOUND, message = "This Inbox item no longer exists.")
}
