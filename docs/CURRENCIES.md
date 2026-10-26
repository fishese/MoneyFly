# Reporting currencies and exchange rates

Settings contains the reporting-currency selector and manual rate refresh. The
initial fallback is HKD. The first imported ledger entry supplies the initial
currency when available; once initialized or explicitly chosen, it stays fixed.
Original stored amounts, currencies and transaction times are preserved.

Transactions defaults to Combined, converting totals, group spending and category
charts into the reporting currency. Separate currencies displays compact cards in
two columns and keeps totals and charts in their original currencies. The mode
is saved. The original-currency filter remains available in either mode. Chart
drill-down uses original entry IDs; editing always opens original money.

## Rate lookup and caching

The source is [Frankfurter v2](https://frankfurter.dev/), a free daily/historical
reference-rate API with no key. HTTPS requests include only currency codes and
calendar dates. No amounts, merchant names, entry IDs or notifications are sent.
The app does not treat these reference rates as a guaranteed card settlement rate.

An entry uses its local calendar date, derived from its saved timestamp in the
device time zone. Requested and actual rate dates are stored in the local cache.
If a date is missing, the latest preceding observation within seven days may be
used; a future observation is never substituted. Future-dated entries remain
unconverted until that date is available, unless given a custom rate.

A network-constrained WorkManager job checks rates daily; Android may defer it.
Opening the app fills missing historical rates and refreshes current-day quotes.
Provisional quotes fetched on their own date are checked once again after that
day ends. Historical settled quotes remain cached. Refresh in Settings or
Transactions rechecks the dates needed by all current foreign entries. Requests
are batched by currency pair and year, with bounded responses and timeouts.
Failures retain cached quotes. Missing conversions are excluded from combined
sums, with an explicit count and partial-total label; the separate view always
shows the original amounts.

## Individual card rates

The entry editor has Use a card or custom rate for foreign entries. Direction is
explicit: target-currency units for one original-currency unit. A rate is positive,
limited to 30 significant digits and 18 decimal places, and bound to its selected
target currency. It overrides the public rate only for that entry and target.
Changing the reporting currency never silently reinterprets a card rate for a
different target. Clearing it returns the entry to daily-rate lookup.

Conversion uses BigDecimal and rounds each converted entry once, half up, to the
reporting currency minor units. Totals add those rounded values, keeping rows,
date groups and charts consistent. Original currency values are never rounded or
overwritten by reporting conversion. Zero-converted values may result from minor
unit rounding and contribute zero, rather than a fabricated minimum amount.

Schema 6→7 adds nullable exchangeRate and exchangeRateCurrency columns. Old rows
keep their original values; old operation fingerprints and JSON replay remain
compatible. Save/edit/undo persist rate overrides through the normal transaction
and revision checks. Settings, display mode and cached rates are app-private and
separate from financial ledger data. No production rates are hardcoded in tests.
