package com.fishese.moneyfly.storage

import com.fishese.moneyfly.capture.NotificationPayload
import com.fishese.moneyfly.ledger.*
import com.fishese.moneyfly.recognition.*

internal class TemplateWriter(private val dao: TemplateDao, private val inbox: InboxDao) {
    suspend fun execute(command: LedgerCommand): CommandResult = when (command) {
        is LedgerCommand.PutTemplate -> put(command.definition, command.expectedRevision)
        is LedgerCommand.SetTemplateEnabled -> {
            val old = dao.latest(command.templateId)
            if (old == null || old.deleted) missing() else put(TemplateCodec.decode(old.definition).copy(enabled = command.enabled), command.expectedRevision)
        }
        is LedgerCommand.DeleteTemplate -> {
            val old = dao.latest(command.templateId)
            when {
                old == null || old.deleted -> missing()
                old.revision != command.expectedRevision -> conflict(old.id, old.revision)
                else -> {
                    val d = TemplateCodec.decode(old.definition).copy(enabled = false, revision = old.revision + 1)
                    dao.putTemplate(TemplateRow(d.id, d.revision, TemplateCodec.encode(d), false, true))
                    ok(d.id, d.revision)
                }
            }
        }
        is LedgerCommand.RepreviewReceipt -> {
            val row = inbox.receipt(command.receiptId)
            val raw = inbox.rawOnce(command.receiptId)
            when {
                row == null || raw == null -> missing()
                row.revision != command.expectedRevision || row.settlement != null || row.removed -> conflict(row.id, row.revision)
                inbox.source(row.sourcePackage)?.ignored == true -> CommandResult(CommandStatus.INVALID, message = "Restore this app before previewing its pending items.")
                else -> {
                    freeze(row, InboxRawCodec.decode(raw.payload, row))
                    inbox.putReceipt(row.copy(revision = row.revision + 1))
                    ok(row.id, row.revision + 1)
                }
            }
        }
        else -> error("Unsupported template command")
    }
    private suspend fun put(input: TemplateDefinition, expected: Long?): CommandResult {
        val old = dao.latest(input.id)
        if (old?.deleted == true) return CommandResult(CommandStatus.INVALID, message = "This template was deleted. Create a new template instead.")
        if (old?.revision != expected) return if (old == null) missing() else conflict(old.id, old.revision)
        val errors = TemplateEngine.validate(input)
        require(errors.isEmpty()) { errors.joinToString(" ") }
        val examples = TemplateEngine.generatedExamples(input)
        require(examples.size == 3 && examples.all { (it.result.status == RecognitionStatus.READY) == it.shouldMatch }) { "The synthetic format variations must pass before saving a template." }
        require(!input.enabled || dao.current().count { it.enabled && it.id != input.id } < 100) { "At most 100 templates may be enabled." }
        val definition = input.copy(revision = (old?.revision ?: 0) + 1)
        // All validation precedes either related write; writer transaction commits both together.
        dao.putTemplate(TemplateRow(definition.id, definition.revision, TemplateCodec.encode(definition), definition.enabled, false))
        if (definition.enabled) {
            val source = inbox.source(definition.sourcePackage) ?: SourceRow(definition.sourcePackage, false, false, 0)
            if (!source.parsingEnabled) inbox.putSource(source.copy(parsingEnabled = true, revision = source.revision + 1))
        }
        return ok(definition.id, definition.revision)
    }
    suspend fun freeze(row: ReceiptRow, payload: NotificationPayload, selectedTemplate: TemplateDefinition? = null): RecognitionResult {
        val result = when {
            selectedTemplate != null -> TemplateEngine.recognize(payload, listOf(selectedTemplate.copy(enabled = true)))
            inbox.source(row.sourcePackage)?.parsingEnabled == true -> TemplateEngine.recognize(payload, dao.current().map { TemplateCodec.decode(it.definition) })
            else -> RecognitionResult(RecognitionStatus.NO_MATCH)
        }
        val json = TemplateCodec.recognition(result)
        val raw = inbox.rawOnce(row.id) ?: return result
        val rawBytes = raw.payload.toByteArray(Charsets.UTF_8).size.toLong()
        if (result.status == RecognitionStatus.NO_MATCH) {
            dao.clearRecognition(row.id); inbox.updateRawBytes(row.id, rawBytes)
        } else {
            dao.putRecognition(RecognitionRow(row.id, json, result.templateId, result.templateRevision))
            inbox.updateRawBytes(row.id, rawBytes + json.toByteArray(Charsets.UTF_8).size)
        }
        return result
    }
    suspend fun requireReview(row: ReceiptRow, result: RecognitionResult, issue: String) {
        val json = TemplateCodec.recognition(result.copy(status = RecognitionStatus.REVIEW_NEEDED, issues = result.issues + issue))
        dao.putRecognition(RecognitionRow(row.id, json, result.templateId, result.templateRevision))
        inbox.rawOnce(row.id)?.let { inbox.updateRawBytes(row.id, it.payload.toByteArray(Charsets.UTF_8).size.toLong() + json.toByteArray(Charsets.UTF_8).size) }
    }
    suspend fun clear(id: String) {
        val row = dao.recognitionOnce(id)
        if (inbox.receipt(id)?.settlement != null && row?.templateId != null)
            dao.putRecognition(row.copy(result = null)) // Compact template provenance survives raw expiry.
        else dao.clearRecognition(id)
    }
    private fun ok(id: String, revision: Long) = CommandResult(CommandStatus.APPLIED, id, revision)
    private fun conflict(id: String, revision: Long) = CommandResult(CommandStatus.CONFLICT, id, revision, message = "This item changed. Review the latest revision.")
    private fun missing() = CommandResult(CommandStatus.NOT_FOUND, message = "This item no longer exists or its content expired.")
}
