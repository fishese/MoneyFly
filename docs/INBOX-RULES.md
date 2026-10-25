# Inbox rules and suggested templates — 0.5.1

The follow-up menus include the resolved app name in brackets, falling back to
the package if unavailable. Similar-message setup proposes a known status pattern
or a conservative exact-field draft. Text with literal wildcard characters or
long/multiline content is left for manual selection, rather than broadened
automatically. The current selected field is shown as selectable, copyable text
on the device. A draft is editable and still requires explicit match review and
Save; an edited pattern is not overwritten when switching notification fields.
All 114 tests pass, lint has 0 errors and 13 warnings, and the signed build is
`artifacts/moneyfly-0.5.1-dev.apk` (version code 6).

This update separates one retained message, a recurring message format, and an
entire source app. No actual notification text, financial values, merchant names,
screenshots, or saved phone UI dumps are included in this review.

## Actions

For left-to-right layouts, swipe left to reveal **Ignore: This / Similar / App**;
swipe right to reveal **Record: Entry / Template**. A completed swipe opens choices
and does not mutate a message. The More actions menu provides equivalent controls.
Entry creation stays independent of template creation.

Ignore similar rules use an exact app, one notification field, and literal text
with `*` as the only wildcard. A review step reports the pending-match count.
Rules can be disabled or deleted in Settings. Matching pending messages stay in
Ignored under the normal raw retention policy; ledger entries and individually
ignored messages retain their own state. Rule changes never dismiss Android
notifications. An ignored message with retained content can still teach a template.

Routine charging titles from Android/System UI and the explicitly identified
launcher status title from Android are hidden by default. These narrow rules are
visible and reversible. Disabled or deleted defaults are not recreated on reopen.
Updated bank/payment notifications are not generically classified as status noise.

## Suggested formats

Matching current notifications can offer optional suggestions for wallet payments,
transit debits, incoming transfers, completed automatic payments, and specific card
SMS layouts. Known format structure from the historical planning handoff is a
candidate, not proof that every app/version/language is supported. Package scope,
current-message preview and synthetic variations are required before enrollment.
Redacted/grouped/truncated, approval, authentication and withdrawal alerts remain
outside these suggestions. Templates never save a financial entry automatically.

Amount, merchant, account hint and other changing text remain variable. Other
changing text can cover dates or footers without claiming it is a transaction
reference or parsing it as an event timestamp. Posting-time provenance remains
explicit. Comma grouping is suggested from an entered amount; an explicit format
selection takes precedence. A custom template can deliberately omit merchant;
the resulting draft stays blank and shows a reminder to review it.

No real samples are shipped as tests or defaults. Example fixtures are invented.
The private review helper is opt-in instrumentation, keeps notification contents
in memory and emits aggregate results only. Its enrollment mode additionally
requires an explicit argument and sends only normal template/repreview commands.

## Validation

The debug APK and test APK build successfully. All 112 automated regression tests
pass; lint reports 0 errors and 13 warnings. APK signature verification passes.
Schema 3→4 adds revisioned ignore rules while preserving the ledger, receipts,
settings and operation history. Migration and rule disable/delete/replay tests
cover restoration and preservation. Native full notification lifecycle acceptance
and local export remain separate pending work.

Build: `artifacts/moneyfly-0.5.0-dev.apk` (version code 5).

The owner-approved device update was installed without resetting the application.
An authorized memory-only check enrolled matching structural templates and
re-previewed pending receipts through the normal writer, then verified persisted
previews after reopening. No financial-save commands were issued. Actual message
contents and device screenshots were not saved in this project. This targeted
check does not establish battery, reboot or every-provider notification acceptance.
