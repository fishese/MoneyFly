# MoneyFly

A local Android expense journal with opt-in notification capture and user-reviewed
payment templates. Enabled templates record clear matching notifications; uncertain
items stay in Inbox for review. Saved entries remain editable.

## Everyday use

- Quick amount-first entry with merchant, notes, date/time, optional category and
  account; edit/delete with conditional Undo.
- Common categories: Meals, Entertainment, Hobby, Transportation, Shopping,
  Groceries, Bills and Health. Browse more categories or create your own.
- Search saved merchant, notes, amount, currency and labels. Combine date,
  category, account, currency and direction filters.
- Group by date, category, merchant or account. Headers show the date/title,
  centered entry count and spending total on the right.
- Tap the category chart for Pie, Bar or List, category shares and entry drill-down.
  Choose combined totals in a reporting currency or separate currency cards. Charts
  count gross expenses and exclude refunds,
  income, transfers and deleted entries.
- Daily and historical exchange rates, manual refresh, original-currency filters,
  and a card/custom rate per entry. See [Currencies](docs/CURRENCIES.md).
- English and Traditional Chinese; light/dark themes follow the device.

The financial ledger starts empty. Starter categories are created once without
renaming existing labels or restoring ones you deliberately removed.

## Notification Inbox

Enable capture from Inbox, review consent, then grant Android notification access.
Pause capture independently of the permission. The app reads only notification
fields visible to the listener, not SMS history or full mailboxes.

Swipe left for Ignore: This item / Similar messages / This app. Swipe right for
Record: Entry / Template. Swipes reveal choices; they do not immediately change a
record. Ignore actions never dismiss the original Android notification.

Similar-message rules can ignore Android summary notifications without a text pattern, or use literal text with `*` for changing parts. Same app only
is on by default; switching it off explicitly applies a rule across apps. Review
matches before saving. Disable rules in Settings to restore pending matches.
Narrow system charging, power-connection and launcher status rules are reversible
defaults. Common camera, clock, weather and other non-financial apps are ignored
by default; the source switch restores capture without changing an existing choice.

Templates extract changing amounts, merchants and optional fields. Generic Smart
Octopus, Google Wallet and AEON alert templates are enabled for sources without
existing settings or templates; they contain no real merchant, amount or account.
Existing choices are never replaced. Other matching suggestions are optional and
must pass current-message and synthetic variation checks. New clear matches record automatically. Saving an enabled template also
applies it to all retained, unrecorded notification messages, including older
messages from apps that reuse a notification. Possible duplicates, ambiguous,
grouped or incomplete notifications remain available for review. Event-time interpretation and account assignment
remain explicit; posting time is labeled when used as a fallback.
Settings → Templates also lets you apply one saved template to existing pending
messages without enabling it for future notifications. View template shows the
exact patterns, conditions, extracted fields and currency settings. Identical
copies are labeled; enrolling an identical format reuses the saved template.

## Build

Use JDK 17, Android SDK platform 36 and the included Gradle wrapper.
Set `sdk.dir` in an ignored `local.properties`, or configure `ANDROID_HOME`.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

Namespace: `com.fishese.moneyfly`; development ID: `com.fishese.moneyfly.debug`.
No signing keys or credentials are included. For a signed local development build,
configure the ignored `.local-signing/development.properties` source used by
`app/build.gradle.kts`; keep its key and passwords local. Release signing is a
separate explicit configuration. Without local development signing, the debug
artifact is unsigned.

## Privacy and project contents

There is no Internet permission, login, cloud service or AI requirement. Capture
is opt-in. Raw notification retention is bounded by age, count and size; saved
entries retain their own provenance. Android application storage protects the
local database; this is not an additional encrypted vault.

Raw notification exports, reference-app research, device dumps, databases,
screenshots, build artifacts and signing material are excluded from Git. Included
notification-shaped fixtures are synthetic. Reusable source-app identifiers and
template grammar are kept without personal samples or captured financial values.

See [category charts](docs/CATEGORY-CHARTS.md) and
[Inbox rules](docs/INBOX-RULES.md) for behavior and validation notes.

This remains a development build. Local export/restore and full physical-device
notification lifecycle and battery-policy acceptance are pending.
