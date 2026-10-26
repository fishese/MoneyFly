package com.fishese.moneyfly.storage

import com.fishese.moneyfly.ledger.*
import java.time.Instant
import org.json.JSONObject

/** Private storage codec. This is not the export format. */
internal object LedgerCodec {
    private fun JSONObject.nullable(key: String): String? = if (isNull(key)) null else getString(key)
    fun entry(entry: LedgerEntry): String = JSONObject().apply {
        val d = entry.draft
        put("id", entry.id); put("revision", entry.revision); put("deleted", entry.deleted)
        put("createdAt", entry.createdAt.toString()); put("updatedAt", entry.updatedAt.toString()); put("lastOperationId", entry.lastOperationId)
        put("amount", d.amount); put("currency", d.currency); put("kind", d.kind.name); put("occurredAt", d.occurredAt.toString())
        put("merchant", d.merchant); put("notes", d.notes); put("categoryId", d.categoryId ?: JSONObject.NULL); put("accountId", d.accountId ?: JSONObject.NULL)
        put("canonicalMerchant", d.canonicalMerchant ?: JSONObject.NULL); put("sourcePackage", d.sourcePackage ?: JSONObject.NULL)
        put("timeOrigin", d.timeOrigin.name); put("timePrecision", d.timePrecision.name); put("categoryOrigin", d.categoryOrigin.name)
    }.toString()
    fun entry(json: String): LedgerEntry = JSONObject(json).let { j -> LedgerEntry(
        j.getString("id"), j.getLong("revision"), EntryDraft(j.getString("amount"), j.getString("currency"), EntryKind.valueOf(j.getString("kind")),
            Instant.parse(j.getString("occurredAt")), j.getString("merchant"), j.getString("notes"), j.nullable("categoryId"), j.nullable("accountId"),
            j.nullable("canonicalMerchant"), j.nullable("sourcePackage"), TimeOrigin.valueOf(j.getString("timeOrigin")), TimePrecision.valueOf(j.getString("timePrecision")), CategoryOrigin.valueOf(j.getString("categoryOrigin"))),
        j.getBoolean("deleted"), Instant.parse(j.getString("createdAt")), Instant.parse(j.getString("updatedAt")), j.getString("lastOperationId")) }
    fun result(result: CommandResult): String = JSONObject().apply {
        put("status", result.status.name); put("entityId", result.entityId ?: JSONObject.NULL)
        put("revision", result.revision ?: JSONObject.NULL); put("entry", result.entry?.let { entry(it) } ?: JSONObject.NULL)
        put("message", result.message ?: JSONObject.NULL)
        result.templateReport?.let { report -> put("templateReport", JSONObject().apply {
            put("checked", report.checked); put("matched", report.matched); put("recorded", report.recorded)
            put("review", report.review); put("unmatched", report.unmatched)
        }) }
    }.toString()
    fun result(json: String): CommandResult = JSONObject(json).let { j -> CommandResult(CommandStatus.valueOf(j.getString("status")), j.nullable("entityId"),
        if (j.isNull("revision")) null else j.getLong("revision"), j.nullable("entry")?.let { entry(it) }, j.nullable("message"),
        templateReport = j.optJSONObject("templateReport")?.let { TemplateApplyReport(it.getInt("checked"), it.getInt("matched"), it.getInt("recorded"), it.getInt("review"), it.getInt("unmatched")) }) }
}
