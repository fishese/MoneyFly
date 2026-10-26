package com.fishese.moneyfly.storage

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.fishese.moneyfly.ledger.*
import com.fishese.moneyfly.capture.*
import com.fishese.moneyfly.recognition.*
import java.time.Instant
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

class RoomLedgerStore(private val database: LedgerDatabase,
    private val receiptDigester: ReceiptDigester = AndroidReceiptDigester(),
    private val clock: () -> Instant = Instant::now,
    private val retention: RawRetention = RawRetention(),
) : LedgerStore, InboxStore, TemplateStore {
    private val dao = database.ledgerDao()
    private val inboxDao = database.inboxDao()
    private val templateDao = database.templateDao()
    private val ignoreRuleDao = database.ignoreRuleDao()
    private val templateWriter = TemplateWriter(templateDao, inboxDao)
    private val ignoreRuleWriter = IgnoreRuleWriter(ignoreRuleDao)
    private val inboxWriter = InboxWriter(inboxDao, dao, receiptDigester, retention, templateWriter, ignoreRuleWriter)
    override val templates = templateDao.templates().map { rows -> rows.map { TemplateCodec.decode(it.definition) } }
    override val ignoreRules = ignoreRuleDao.rules().map { rows -> rows.map { it.toRule() } }
    override val entries = dao.entries().map { rows -> rows.map { it.toEntry() }.sortedWith(compareByDescending<LedgerEntry> { it.draft.occurredAt }.thenBy { it.id }) }
    override val categories = dao.categories().map { rows -> rows.map { Category(it.id, it.name, it.revision) } }
    override val accounts = dao.accounts().map { rows -> rows.map { AccountLabel(it.id, it.name, AccountKind.valueOf(it.kind), it.revision) } }
    override val aliases = dao.aliases().map { rows -> rows.map { MerchantAlias(it.id, it.spelling, it.canonicalName, it.revision) } }
    override val rules = dao.rules().map { rows -> rows.map { CategoryRule(it.id, RuleField.valueOf(it.field), LiteralMatch.valueOf(it.match), it.literal, it.categoryId, it.priority, it.sourcePackage, it.accountId, it.revision) } }
    override val sources = inboxDao.sources().map { rows -> rows.map { SourcePreference(it.sourcePackage, it.ignored, it.parsingEnabled, it.revision) } }
    override val captureSettings = inboxDao.settings().map { CaptureSettings(it?.consent ?: false, it?.revision ?: 0) }
    override val inbox = combine(inboxDao.receipts(), inboxDao.raw(), inboxDao.sources(), templateDao.recognition(), ignoreRuleDao.rules()) { receipts, raw, sources, recognition, ignoreRuleRows ->
        val cutoff = clock().minus(retention.maxAge).toEpochMilli()
        val rawById = raw.filter { it.capturedAt > cutoff }.associateBy { it.receiptId }
        val ignoredSources = sources.filter { it.ignored }.map { it.sourcePackage }.toSet()
        val recognitionById = recognition.associateBy { it.receiptId }
        val receiptsByEpisode = receipts.groupBy { it.episodeId }
        val compiledIgnoreRules = ignoreRuleRows.mapNotNull { IgnoreRuleMatcher.compile(it.toRule()) }
        val appIgnoreRules = compiledIgnoreRules.filter { it.rule.sameAppOnly }.groupBy { it.rule.sourcePackage }
        val crossAppIgnoreRules = compiledIgnoreRules.filterNot { it.rule.sameAppOnly }
        receipts.mapNotNull { row ->
            val content = rawById[row.id]
            if (content == null && row.settlement == null) null else {
                val payload = content?.let { InboxRawCodec.decode(it.payload, row) }
                val provenance = recognitionById[row.id]
                val recognized = if (content == null) null else provenance?.result?.let { TemplateCodec.recognition(it) }
                val ignoreRule = payload?.takeIf { row.settlement == null && !row.removed }
                    ?.let { p -> IgnoreRuleMatcher.matchingRule(p, appIgnoreRules[p.sourcePackage].orEmpty() + crossAppIgnoreRules) }
                val status = when {
                    row.removed -> InboxStatus.REMOVED
                    row.settlement == "SAVED" -> InboxStatus.SAVED
                    row.settlement == "LINKED_DUPLICATE" -> InboxStatus.LINKED_DUPLICATE
                    row.settlement == "IGNORED" || row.sourcePackage in ignoredSources || ignoreRule != null -> InboxStatus.IGNORED
                    row.contentRevision > 1 -> InboxStatus.REVIEW_NEEDED
                    recognized?.status == RecognitionStatus.REVIEW_NEEDED -> InboxStatus.REVIEW_NEEDED
                    recognized?.status == RecognitionStatus.READY -> InboxStatus.READY
                    else -> InboxStatus.UNRECOGNIZED
                }
                val relatedReceipts = receiptsByEpisode[row.episodeId].orEmpty().filter { it.id != row.id }
                InboxItem(row.id, row.revision, row.sourcePackage, Instant.parse(row.postedAt), Instant.parse(row.observedAt), row.contentRevision,
                    status, payload, row.entryId,
                    relatedReceipts.mapNotNull { it.entryId }.distinct(), row.contentRevision > 1 || relatedReceipts.any { it.contentRevision > row.contentRevision },
                    recognized?.draft, recognized?.issues.orEmpty(), provenance?.templateId, provenance?.templateRevision,
                    ignoreRule?.id, ignoreRule?.revision)
            }
        }.sortedWith(compareByDescending<InboxItem> { it.observedAt }.thenBy { it.id })
    }.flowOn(Dispatchers.Default)

    override suspend fun execute(command: LedgerCommand): CommandResult = database.withTransaction {
        if (command.operationId.isBlank() || command.operationId.length > 200) return@withTransaction CommandResult(CommandStatus.INVALID, message = "A valid operation ID is required.")
        if (command is LedgerCommand.CaptureNotification) {
            val p = command.payload
            val textSize = p.title.length.toLong() + p.body.length + p.bigText.length + p.subText.length + p.textLines.sumOf { it.length.toLong() }
            if (p.textLines.size > 100 || textSize > 1_000_000 || p.notificationKey.length > 2000 || p.userScope.length > 500 || p.sourcePackage.length > 300)
                return@withTransaction CommandResult(CommandStatus.INVALID, message = "Notification content exceeds capture limits.")
        }
        val fingerprint = if (command is LedgerCommand.CaptureNotification || command is LedgerCommand.NotificationRemoved)
            "hmac:" + receiptDigester.digest(CommandFingerprint.canonical(command)) else CommandFingerprint.of(command)
        val existing = dao.operation(command.operationId)
        if (existing != null) return@withTransaction if (existing.fingerprint == fingerprint) LedgerCodec.result(existing.result).copy(replayed = true)
            else CommandResult(CommandStatus.INVALID, message = "This operation ID was already used for a different action.")
        var previous: LedgerEntry? = null
        val now = clock()
        val result = try {
            inboxWriter.prune(now)
            when (command) {
                is LedgerCommand.Save -> {
                    require(command.entryId.isNotBlank()) { "An entry ID is required." }
                    val old = dao.entry(command.entryId)?.toEntry()
                    if (old != null) conflict(old) else {
                        val draft = validate(command.draft)
                        val entry = LedgerEntry(command.entryId, 1, draft, false, now, now, command.operationId)
                        dao.putEntry(entry.toRow()); applied(entry)
                    }
                }
                is LedgerCommand.Edit -> {
                    val old = dao.entry(command.entryId)?.toEntry()
                    when {
                        old == null -> missing()
                        old.revision != command.expectedRevision || old.deleted -> conflict(old)
                        else -> {
                            val draft = validate(command.draft)
                            previous = old
                            val updated = old.copy(revision = old.revision + 1, draft = draft, updatedAt = now, lastOperationId = command.operationId)
                            dao.putEntry(updated.toRow()); applied(updated)
                        }
                    }
                }
                is LedgerCommand.Delete -> {
                    val old = dao.entry(command.entryId)?.toEntry()
                    when {
                        old == null -> missing()
                        old.revision != command.expectedRevision || old.deleted -> conflict(old)
                        else -> {
                            previous = old
                            val updated = old.copy(revision = old.revision + 1, deleted = true, updatedAt = now, lastOperationId = command.operationId)
                            dao.putEntry(updated.toRow()); inboxDao.markEntryRemoved(updated.id, true); applied(updated)
                        }
                    }
                }
                is LedgerCommand.Undo -> {
                    val target = dao.operation(command.targetOperationId)
                    val before = target?.previousEntry?.let { LedgerCodec.entry(it) }
                    val targetResult = target?.let { LedgerCodec.result(it.result) }
                    val old = before?.let { dao.entry(it.id)?.toEntry() }
                    when {
                        before == null || targetResult?.applied != true -> CommandResult(CommandStatus.INVALID, message = "This action cannot be undone.")
                        old == null -> missing()
                        old.revision != command.expectedRevision || old.revision != targetResult.revision || old.lastOperationId != command.targetOperationId -> conflict(old)
                        else -> {
                            validate(before.draft)
                            previous = old
                            val updated = before.copy(revision = old.revision + 1, updatedAt = now, lastOperationId = command.operationId)
                            dao.putEntry(updated.toRow()); inboxDao.markEntryRemoved(updated.id, updated.deleted); applied(updated)
                        }
                    }
                }
                is LedgerCommand.PutCategory -> {
                    val old = dao.category(command.id)
                    checkRevision(command.id, command.expectedRevision, old?.revision) ?: run {
                        requireNotRetired("category", command.id)
                        val row = CategoryRow(validId(command.id), validLabel(command.name), (old?.revision ?: 0) + 1)
                        dao.putCategory(row); applied(row.id, row.revision)
                    }
                }
                is LedgerCommand.DeleteCategory -> {
                    val old = dao.category(command.id)
                    checkRevision(command.id, command.expectedRevision, old?.revision) ?: run {
                        dao.clearCategory(command.id, now.toString(), command.operationId)
                        for (id in dao.categoryRuleIds(command.id)) dao.retire(RetiredEntityRow("rule", id))
                        dao.deleteCategoryRules(command.id); dao.deleteCategory(command.id)
                        dao.retire(RetiredEntityRow("category", command.id))
                        applied(command.id, old!!.revision + 1)
                    }
                }
                is LedgerCommand.PutAccount -> {
                    val old = dao.account(command.id)
                    checkRevision(command.id, command.expectedRevision, old?.revision) ?: run {
                        requireNotRetired("account", command.id)
                        val row = AccountRow(validId(command.id), validLabel(command.name), command.kind.name, (old?.revision ?: 0) + 1)
                        dao.putAccount(row); applied(row.id, row.revision)
                    }
                }
                is LedgerCommand.DeleteAccount -> {
                    val old = dao.account(command.id)
                    checkRevision(command.id, command.expectedRevision, old?.revision) ?: run {
                        dao.clearAccount(command.id, now.toString(), command.operationId)
                        for (id in dao.accountRuleIds(command.id)) dao.retire(RetiredEntityRow("rule", id))
                        dao.deleteAccountRules(command.id); dao.deleteAccount(command.id)
                        dao.retire(RetiredEntityRow("account", command.id))
                        applied(command.id, old!!.revision + 1)
                    }
                }
                is LedgerCommand.PutAlias -> {
                    val old = dao.alias(command.id)
                    checkRevision(command.id, command.expectedRevision, old?.revision) ?: run {
                        requireNotRetired("alias", command.id)
                        val spelling = validLabel(command.spelling)
                        val normalized = Categorization.normalize(spelling)
                        require(dao.aliasWithSpelling(normalized, command.id) == null) { "This merchant spelling already has an alias." }
                        val row = AliasRow(validId(command.id), spelling, normalized, validLabel(command.canonicalName), (old?.revision ?: 0) + 1)
                        dao.putAlias(row); applied(row.id, row.revision)
                    }
                }
                is LedgerCommand.DeleteAlias -> {
                    val old = dao.alias(command.id)
                    checkRevision(command.id, command.expectedRevision, old?.revision) ?: run { dao.deleteAlias(command.id); dao.retire(RetiredEntityRow("alias", command.id)); applied(command.id, old!!.revision + 1) }
                }
                is LedgerCommand.PutRule -> {
                    val rule = command.rule
                    val old = dao.rule(rule.id)
                    checkRevision(rule.id, command.expectedRevision, old?.revision) ?: run {
                        requireNotRetired("rule", rule.id)
                        require(dao.category(rule.categoryId) != null) { "Choose an existing category." }
                        require(rule.accountId == null || dao.account(rule.accountId) != null) { "Choose an existing account label." }
                        val row = RuleRow(validId(rule.id), rule.field.name, rule.match.name, validLabel(rule.literal), rule.categoryId, rule.priority, rule.sourcePackage, rule.accountId, (old?.revision ?: 0) + 1)
                        dao.putRule(row); applied(row.id, row.revision)
                    }
                }
                is LedgerCommand.DeleteRule -> {
                    val old = dao.rule(command.id)
                    checkRevision(command.id, command.expectedRevision, old?.revision) ?: run { dao.deleteRule(command.id); dao.retire(RetiredEntityRow("rule", command.id)); applied(command.id, old!!.revision + 1) }
                }
                is LedgerCommand.PutTemplate, is LedgerCommand.SetTemplateEnabled, is LedgerCommand.DeleteTemplate, is LedgerCommand.RepreviewReceipt -> {
                    val saved = templateWriter.execute(command)
                    val source = when (command) {
                        is LedgerCommand.PutTemplate -> command.definition.takeIf { it.enabled }?.sourcePackage
                        is LedgerCommand.SetTemplateEnabled -> if (command.enabled) templateDao.latest(command.templateId)?.let { TemplateCodec.decode(it.definition).sourcePackage } else null
                        else -> null
                    }
                    if (saved.applied && source != null) inboxWriter.applyTemplateToPending(source, now, command.operationId, ::validate)
                    saved
                }
                is LedgerCommand.ApplyTemplateToPending -> {
                    val template = templateDao.latest(command.templateId)
                    when {
                        template == null || template.deleted -> CommandResult(CommandStatus.NOT_FOUND, message = "This template no longer exists.")
                        template.revision != command.expectedRevision -> CommandResult(CommandStatus.CONFLICT, template.id, template.revision, message = "This template changed. Review the latest version.")
                        else -> {
                            val definition = TemplateCodec.decode(template.definition)
                            inboxWriter.applyTemplateToPending(definition.sourcePackage, now, command.operationId, ::validate, definition)
                            CommandResult(CommandStatus.APPLIED, template.id, template.revision)
                        }
                    }
                }
                is LedgerCommand.PutIgnoreRule, is LedgerCommand.SetIgnoreRuleEnabled, is LedgerCommand.DeleteIgnoreRule -> ignoreRuleWriter.execute(command)
                else -> inboxWriter.execute(command, now, ::validate)
            }
        } catch (e: IllegalArgumentException) { CommandResult(CommandStatus.INVALID, message = e.message ?: "Check the entered values.") }
        inboxWriter.prune(now)
        dao.putOperation(OperationRow(command.operationId, fingerprint, LedgerCodec.result(result), previous?.let { LedgerCodec.entry(it) }))
        result
    }

    private suspend fun validate(draft: EntryDraft): EntryDraft {
        val normalized = Money.normalize(draft.amount, draft.currency)
        val year = try { draft.occurredAt.atZone(java.time.ZoneOffset.UTC).year }
            catch (_: java.time.DateTimeException) { throw IllegalArgumentException("Choose a usable date.") }
        require(year in 1..9999) { "Choose a usable date." }
        require(draft.categoryId == null || dao.category(draft.categoryId) != null) { "The category no longer exists." }
        require(draft.accountId == null || dao.account(draft.accountId) != null) { "The account label no longer exists." }
        require(draft.merchant.length <= 2000 && draft.notes.length <= 20000) { "Merchant or notes are too long." }
        return draft.copy(amount = normalized, categoryOrigin = if (draft.categoryId == null) CategoryOrigin.UNASSIGNED else draft.categoryOrigin)
    }
    private fun validId(id: String): String { require(id.isNotBlank() && id.length <= 200) { "A valid ID is required." }; return id }
    private suspend fun requireNotRetired(kind: String, id: String) { require(dao.retired(kind, id) == null) { "This item was deleted. Create a new item instead." } }
    private fun validLabel(name: String): String { require(name.isNotBlank() && name.length <= 500) { "Enter a label of 1–500 characters." }; return name.trim() }
    private fun checkRevision(id: String, expected: Long?, actual: Long?): CommandResult? = when {
        expected == null && actual == null -> null
        actual == null -> missing()
        expected != actual -> CommandResult(CommandStatus.CONFLICT, id, actual, message = "This item changed. Review the latest version.")
        else -> null
    }
    private fun conflict(entry: LedgerEntry) = CommandResult(CommandStatus.CONFLICT, entry.id, entry.revision, entry, "This entry changed. Review the latest version.")
    private fun missing() = CommandResult(CommandStatus.NOT_FOUND, message = "This item no longer exists.")
    private fun applied(entry: LedgerEntry) = CommandResult(CommandStatus.APPLIED, entry.id, entry.revision, entry)
    private fun applied(id: String, revision: Long) = CommandResult(CommandStatus.APPLIED, id, revision)

    companion object {
        /** A new app-owned database; deliberately has no old-database migrations or imports. */
        fun open(context: Context): RoomLedgerStore = RoomLedgerStore(Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, "moneyfly_next_ledger_v1.db")
            .addMigrations(LEDGER_MIGRATION_1_2, LEDGER_MIGRATION_2_3, LEDGER_MIGRATION_3_4, LEDGER_MIGRATION_4_5, LEDGER_MIGRATION_5_6)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) { seedDefaultStatusRules(db) }
            }).build())
    }
}

internal fun EntryRow.toEntry() = LedgerEntry(id, revision,
    EntryDraft(amount, currency, EntryKind.valueOf(kind), Instant.parse(occurredAt), merchant, notes, categoryId, accountId,
        canonicalMerchant, sourcePackage, TimeOrigin.valueOf(timeOrigin), TimePrecision.valueOf(timePrecision), CategoryOrigin.valueOf(categoryOrigin)),
    deleted, Instant.parse(createdAt), Instant.parse(updatedAt), lastOperationId)
internal fun LedgerEntry.toRow(): EntryRow = draft.let { d -> EntryRow(id, revision, d.amount, d.currency, d.kind.name, d.occurredAt.toString(), d.merchant, d.notes,
    d.categoryId, d.accountId, d.canonicalMerchant, d.sourcePackage, d.timeOrigin.name, d.timePrecision.name, d.categoryOrigin.name,
    deleted, createdAt.toString(), updatedAt.toString(), lastOperationId) }
