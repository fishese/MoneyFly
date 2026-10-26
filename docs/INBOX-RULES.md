# Notification recording and ignore rules

Enabled templates automatically record new notifications with a clear match.
Capture consent and source parsing must be enabled. Entries and receipt settlement
commit together in the ledger transaction. Replayed delivery does not book twice.

Possible duplicates, conflicting templates, summary,
redacted and truncated notifications remain in Inbox for review. Duplicate
suggestions never automatically merge two purchases. A repeated amount without
a merchant is also held for review when a nearby entry could be the same payment.

Saving or enabling a template also applies it to all retained pending messages
from that app, including older versions when an app reuses its notification. Clear
matches are booked immediately, even when new notification capture is paused.
Saved items, individually ignored, rule-ignored,
app-ignored and expired items are excluded. Possible duplicates remain pending
for review. Disable a template or source parsing to pause future automatic
recording. Existing entries stay editable and unchanged.
Saved receipts retain the template ID/revision after retained raw content expires.

Settings → Templates also has **Apply to existing messages** for a one-time run
of that specific template. It can run while the template is disabled, but does
not enable it for future notifications. Every retained,
unrecorded message is considered; saved, ignored, app-ignored and expired messages
are skipped. Possible duplicates and unclear matches stay in Inbox.

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

## Reviewing and overlapping templates

View template shows the stored revision, complete field patterns, slot limits,
matching conditions, number format, currency evidence and fixed values. Patterns
and conditions are selectable text. Existing identical copies are labeled with
their IDs; no templates are silently deleted. New identical enrollments reuse the
existing format. Different templates for one app are supported. Automatic matching
requires review when extracted drafts disagree. A one-time Apply uses only the
selected template.

Apply reports checked, matched, recorded, review-needed and nonmatching counts.
Reports survive operation replay. Applying a nonmatching template preserves the
preview and reason produced by another template. Notification revision alone does
not stop a retained purchase from being recorded. Normal financial duplicate checks
still apply, including protection against recreating a related deleted purchase.
