# Transaction actions and account rules

Swipe a transaction left for category actions, or right for account actions. The
first choice changes that entry only. The second creates a rule for future entries;
it does not rewrite existing entries. Saved account rules can be reviewed or deleted
in Settings → Account labels → Account rules.

Account rules can match an exact merchant name (ignoring case and extra whitespace)
or a notification source app. Matching rules apply only when no account was chosen
explicitly. Merchant rules take precedence over app rules; if equally specific rules
disagree, the entry remains unassigned. Account assignment happens before category
rules are evaluated, so an account-scoped category rule can also match. Choosing
Unassigned in the editor suppresses account rules for that save.

An account can have an optional short Unicode icon or emoji. Edit it in Settings →
Account labels. The transaction list shows the icon when one is set, while account
names remain available in the editor, search, filters and account grouping. Existing
accounts keep no icon until one is chosen.

Select in Transactions enables checkboxes. Select all results includes every entry
matching the current filters, including collapsed groups; selected entries remain
selected if filters change. Category and account assignments affect only selected
entries. Removal requires confirmation. Each change checks the entry revision;
conflicts are skipped and reported. An immediate Undo is offered for successful
changes, and checks revisions again before restoring them.

The Inbox opens on entries needing attention. Ready means recognized and waiting
for a manual save; Review needed and Unrecognized are also included. All, Saved and
Ignored remain accessible. Notification source capture and template parsing are
independent switches: turning off capture prevents new notifications entering the
Inbox; turning off template parsing still captures them without automatic template
recognition. App names come from Android when available, with package names shown.
