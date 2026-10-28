package com.fishese.moneyfly.storage

import com.fishese.moneyfly.ledger.*
import com.fishese.moneyfly.recognition.*
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

internal object TemplateCodec {
    private fun JSONObject.optional(key: String) = if (isNull(key)) null else getString(key)
    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
    fun encode(d: TemplateDefinition): String = JSONObject().apply {
        put("id", d.id); put("name", d.name); put("source", d.sourcePackage); put("revision", d.revision); put("enabled", d.enabled)
        put("kind", d.kind.name); put("currency", d.fixedCurrency ?: JSONObject.NULL); put("evidence", d.fixedCurrencyEvidence?.name ?: JSONObject.NULL)
        put("merchant", d.fixedMerchant ?: JSONObject.NULL); put("numbers", d.numberConvention.name)
        put("patterns", JSONArray(d.patterns.map { p -> JSONObject().apply { put("field", p.field.name); if (p.section) put("section", true); put("segments", JSONArray(p.segments.map { s -> JSONObject().apply {
            when (s) { is PatternSegment.Literal -> put("literal", s.text); is PatternSegment.Slot -> { put("slot", s.name.name); put("max", s.maxLength); if (s.numericOnly) put("numeric", true); if (s.linked) put("linked", true) } }
        } })) } }))
        put("predicates", JSONArray(d.predicates.map { p -> JSONObject().apply { put("field", p.field.name); put("operation", p.operation.name); put("literal", p.literal); put("ignoreCase", p.ignoreCase) } }))
        put("tokens", JSONArray(d.currencyTokens.map { t -> JSONObject().apply { put("token", t.token); put("currency", t.currency); put("confirmed", t.userConfirmedAmbiguous) } }))
    }.toString()
    fun decode(json: String): TemplateDefinition = JSONObject(json).let { j -> TemplateDefinition(j.getString("id"), j.getString("name"), j.getString("source"),
        j.getJSONArray("patterns").objects().map { p -> FieldPattern(MessageField.valueOf(p.getString("field")), p.getJSONArray("segments").objects().map { s ->
            if (s.has("literal")) PatternSegment.Literal(s.getString("literal")) else PatternSegment.Slot(SlotName.valueOf(s.getString("slot")), s.getInt("max"), s.optBoolean("numeric", false), s.optBoolean("linked", false)) }, p.optBoolean("section", false)) },
        j.getJSONArray("predicates").objects().map { p -> TemplatePredicate(MessageField.valueOf(p.getString("field")), PredicateOperation.valueOf(p.getString("operation")), p.getString("literal"), p.getBoolean("ignoreCase")) },
        EntryKind.valueOf(j.getString("kind")), j.optional("currency"), j.optional("evidence")?.let { FixedCurrencyEvidence.valueOf(it) }, j.optional("merchant"),
        j.getJSONArray("tokens").objects().map { t -> CurrencyToken(t.getString("token"), t.getString("currency"), t.getBoolean("confirmed")) }, NumberConvention.valueOf(j.getString("numbers")), j.getBoolean("enabled"), j.getLong("revision")) }

    fun recognition(r: RecognitionResult): String = JSONObject().apply {
        put("status", r.status.name); put("template", r.templateId ?: JSONObject.NULL); put("revision", r.templateRevision ?: JSONObject.NULL)
        put("issues", JSONArray(r.issues)); put("draft", r.draft?.let { d -> JSONObject().apply {
            put("amount", d.amount); put("currency", d.currency); put("kind", d.kind.name); put("time", d.occurredAt.toString()); put("merchant", d.merchant); put("notes", d.notes)
            put("source", d.sourcePackage ?: JSONObject.NULL); put("timeOrigin", d.timeOrigin.name); put("timePrecision", d.timePrecision.name)
        } } ?: JSONObject.NULL)
    }.toString()
    fun recognition(json: String): RecognitionResult = JSONObject(json).let { j ->
        val issues = j.getJSONArray("issues")
        RecognitionResult(RecognitionStatus.valueOf(j.getString("status")), if (j.isNull("draft")) null else j.getJSONObject("draft").let { d -> EntryDraft(
            d.getString("amount"), d.getString("currency"), EntryKind.valueOf(d.getString("kind")), Instant.parse(d.getString("time")), d.getString("merchant"), d.getString("notes"),
            sourcePackage = d.optional("source"), timeOrigin = TimeOrigin.valueOf(d.getString("timeOrigin")), timePrecision = TimePrecision.valueOf(d.getString("timePrecision"))) },
            List(issues.length()) { issues.getString(it) }, j.optional("template"), if (j.isNull("revision")) null else j.getLong("revision"))
    }
}
