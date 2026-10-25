package com.fishese.moneyfly.storage

import com.fishese.moneyfly.capture.NotificationPayload
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

internal object InboxRawCodec {
    fun encode(p: NotificationPayload): String = JSONObject().apply {
        put("title", p.title); put("body", p.body); put("bigText", p.bigText); put("subText", p.subText)
        put("textLines", JSONArray(p.textLines)); put("groupSummary", p.groupSummary)
        put("redacted", p.redacted); put("truncated", p.truncated)
    }.toString()
    fun decode(raw: String, row: ReceiptRow): NotificationPayload = JSONObject(raw).let { j ->
        val lines = j.getJSONArray("textLines")
        NotificationPayload(row.sourcePackage, "", "", Instant.parse(row.postedAt), Instant.parse(row.observedAt),
            j.getString("title"), j.getString("body"), j.getString("bigText"), List(lines.length()) { lines.getString(it) },
            j.getString("subText"), j.getBoolean("groupSummary"), j.getBoolean("redacted"), j.getBoolean("truncated"))
    }
}
