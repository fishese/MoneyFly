# Notification recording and ignore rules

Enabled templates automatically record new notifications with a clear match.
Capture consent and source parsing must be enabled. Entries and receipt settlement
commit together in the ledger transaction. Replayed delivery does not book twice.

Changed notifications, possible duplicates, conflicting templates, summary,
redacted and truncated notifications remain in Inbox for review. Duplicate
suggestions never automatically merge two purchases. A repeated amount without
a merchant is also held for review when a nearby entry could be the same payment.

Saving or enabling a template also applies it to the latest retained pending
version of each notification from that app, including the current example. Clear
matches are booked immediately, even when new notification capture is paused.
Older superseded versions, saved items, individually ignored, rule-ignored,
app-ignored and expired items are excluded. Possible duplicates remain pending
for review. Disable a template or source parsing to pause future automatic
recording. Existing entries stay editable and unchanged.
Saved receipts retain the template ID/revision after retained raw content expires.

## Ignore similar messages

Open a message, choose **More actions → Similar messages** (also available from
the Ignore swipe menu). Enable **Summary notifications only** to match Android's
group-summary flag, independent of title, body or changing message counts. This
option starts on when opened from a summary notification. No text pattern is needed.
Individual notifications are not matched by summary-only rules.

**Same app only** starts on. Turn it off explicitly to apply the rule across apps.
Review the pending match count before saving. Switch between summary-only and text
matching without losing the unsaved text pattern. Text rules use a selected field
and literal text with `*` for changing parts; the current field is copyable.

Disable or delete rules in Settings to restore pending matches while retained
content remains available. Rules do not alter saved entries or dismiss Android
notifications. Narrow system charging/launcher status rules remain reversible
defaults. Rules are checked before automatic recording.

## Privacy and verification

Only invented message fixtures are used in repository tests and emulator review.
No personal notifications, exports, phone UI dumps or signing files are committed.
Database upgrades preserve earlier text rules and app scope. Summary matching uses
a separate persisted flag; existing rules keep their original text behavior.
