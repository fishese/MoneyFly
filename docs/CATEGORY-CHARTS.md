# Categories and spending charts — 0.6

Common categories are Meals, Entertainment, Hobby, Transportation, Shopping,
Groceries, Bills and Health. They are added once through the normal ledger writer;
existing labels and their assignments are preserved. Deleted or renamed starter
labels are not recreated automatically. More categories offers a searchable
catalog plus custom creation, available from Settings and the entry picker.

Transactions includes a compact category chart for the current filtered view.
Tap to open Pie, Bar or List; tap a slice/category for exact amount, rounded share,
entry count and recent entries. Show these entries filters the journal by category,
Expense and the current original-currency filter. Reporting supports combined
currency conversion or separate currencies (see [Currencies](CURRENCIES.md)). The chart measures gross expenses;
income, refunds, transfers and deleted entries are excluded. Decimal sums remain
exact; floating point is used only to draw ratios. Percentages round independently.

Group headers span the journal width, with indented entry content below. They
start with the plain date/title, center the count, and place spending totals at
the right. Multiple currencies occupy separate lines. Headers remain collapsible.

Ignore similar has Same app only enabled by default. Turning it off explicitly
applies the pattern across apps; changing scope invalidates the reviewed count.
Schema 4→5 preserves old rules as same-app-only and preserves retry fingerprints.
Built-in system status rules remain app-scoped.

The Inbox labels notifications carrying Android's group-summary flag. A summary
is a real posted notification representing other notifications; MoneyFly does not
generate the source app's unread-message count. Android also supports automatic
grouping ([Android notification groups](https://developer.android.com/develop/ui/views/notifications/group)).

Validation: 126 automated tests pass, including exact category sums, separate
currencies, large-value ratios, slice selection, style switching, category setup,
deletion/retry behavior, cross-app matching and schema migration. Lint has no
errors (26 warnings). The debug and instrumentation APKs build successfully.
Native visual review used the isolated synthetic-data emulator in light mode,
dark mode and 135% text. No actual phone notification contents or screenshots
were saved in the project.

Synthetic visual-review artifacts are kept locally and excluded from version control.

Build: `artifacts/moneyfly-0.6.0-dev.apk` (version code 7). Physical notification
lifecycle/battery acceptance and local export remain separate pending work.
# Automatic categorization

Snacks is included among the default categories, including on existing installations.
Starting rules map the exact merchant names Minibus and MTR Corporation Limited to
Transportation. Matching ignores case and repeated whitespace. Rules can be edited
or deleted under Settings → Category rules and are only installed once.

When editing an expense, a category choice affects only that entry. After choosing
a category, use “Always categorize this merchant…” to create or update an exact-name
rule for future expenses. The editor previews a matching rule and allows a one-off
override. Rules also apply to automatically recorded notification expenses. Rules
with the same highest priority that disagree leave the expense uncategorized.

Settings → Category rules → Categorize existing expenses applies the current rules
to uncategorized expenses after confirmation. It skips existing categories, income,
and conflicting rules. Each entry update checks its revision, so an entry changed
concurrently is skipped and counted as a failure. Financial amounts are untouched.
