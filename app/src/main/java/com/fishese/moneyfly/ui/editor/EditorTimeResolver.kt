package com.fishese.moneyfly.ui

import com.fishese.moneyfly.ledger.EntryDraft
import com.fishese.moneyfly.ledger.TimeOrigin
import com.fishese.moneyfly.ledger.TimePrecision
import java.time.*

internal enum class EditorTimeIssue { INVALID, GAP, OVERLAP }
internal data class EditorTimeResolution(
    val instant: Instant? = null, val origin: TimeOrigin? = null, val precision: TimePrecision? = null,
    val issue: EditorTimeIssue? = null, val preservesOriginal: Boolean = false,
)

/** A wall clock edit must not silently shift through a gap or select one of two offsets. */
internal fun resolveEditorTime(date: String, time: String, zone: ZoneId, initial: EntryDraft, preserveOriginal: Boolean): EditorTimeResolution {
    val local = runCatching { LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time)) }.getOrNull()
        ?: return EditorTimeResolution(issue = EditorTimeIssue.INVALID)
    val observed = initial.occurredAt.atZone(zone)
    val unchanged = local == observed.toLocalDateTime().withSecond(0).withNano(0)
    if (unchanged && preserveOriginal) return EditorTimeResolution(initial.occurredAt, initial.timeOrigin, initial.timePrecision, preservesOriginal = true)
    val offsets = zone.rules.getValidOffsets(local)
    if (offsets.isEmpty()) return EditorTimeResolution(issue = EditorTimeIssue.GAP)
    val offset = if (offsets.size == 1) offsets.single()
        else if (unchanged && observed.offset in offsets) observed.offset
        else return EditorTimeResolution(issue = EditorTimeIssue.OVERLAP)
    return EditorTimeResolution(local.toInstant(offset), TimeOrigin.USER, TimePrecision.MINUTE)
}
