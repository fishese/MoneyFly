package com.fishese.moneyfly.fx

import android.content.Context
import com.fishese.moneyfly.ledger.LedgerEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun interface FxRateClient {
    suspend fun fetch(source: String, target: String, dates: Set<LocalDate>): List<FxQuote>
}

/** Fetches currency codes and calendar dates only. No amounts, merchants or notification text leave the device. */
class FrankfurterRateClient(private val now: () -> Long = System::currentTimeMillis) : FxRateClient {
    override suspend fun fetch(source: String, target: String, dates: Set<LocalDate>): List<FxQuote> = withContext(Dispatchers.IO) {
        require(supportedCurrency(source) && supportedCurrency(target))
        val quotes = mutableListOf<FxQuote>()
        for (year in dates.groupBy { it.year }.values) {
            val start = year.min().minusDays(7); val end = year.max()
            val connection = URL("https://api.frankfurter.dev/v2/rates?base=$source&quotes=$target&from=$start&to=$end").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000; connection.readTimeout = 20_000
                connection.setRequestProperty("Accept", "application/json")
                check(connection.responseCode == 200) { "Exchange-rate service unavailable." }
                val bytes = connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= 2_000_000) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                check(bytes.size <= 2_000_000) { "Exchange-rate response too large." }
                quotes += parseRateResponse(bytes.toString(Charsets.UTF_8), source, target, year.toSet(), now())
            } finally { connection.disconnect() }
        }
        quotes
    }
}

/** Holidays may use the latest available preceding daily rate, never a future rate. */
internal fun parseRateResponse(json: String, source: String, target: String, dates: Set<LocalDate>, fetchedAt: Long): List<FxQuote> {
    val rows = JSONArray(json)
    val available = (0 until rows.length()).mapNotNull { index ->
        val row = rows.getJSONObject(index)
        if (row.getString("base") != source || row.getString("quote") != target) return@mapNotNull null
        val date = LocalDate.parse(row.getString("date"))
        val rate = validatedEntryRate(java.math.BigDecimal(row.getString("rate")).toPlainString(), target)!!
        date to rate
    }.sortedBy { it.first }
    return dates.mapNotNull { date ->
        available.lastOrNull { it.first <= date && it.first >= date.minusDays(7) }
            ?.let { FxQuote(date, source, target, it.second, it.first, fetchedAt) }
    }
}

class FxRepository(context: Context, private val client: FxRateClient = FrankfurterRateClient(),
    private val clock: () -> Long = System::currentTimeMillis) {
    private val preferences = context.applicationContext.getSharedPreferences("reporting-currency-and-rates", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val refreshMutex = Mutex()
    private var currencyGeneration = 0L
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()

    private fun read(): FxSnapshot = runCatching {
        val json = JSONObject(preferences.getString("state", "{}")!!)
        val quotes = json.optJSONArray("quotes") ?: JSONArray()
        val cache = (0 until quotes.length()).mapNotNull { index -> runCatching {
            val q = quotes.getJSONObject(index)
            FxQuote(LocalDate.parse(q.getString("date")), q.getString("source"), q.getString("target"),
                validatedEntryRate(q.getString("rate"), q.getString("target"))!!, LocalDate.parse(q.getString("rateDate")), q.getLong("fetchedAt"))
        }.getOrNull() }.associateBy { it.key }
        FxSnapshot(json.optString("currency", "HKD").takeIf(::supportedCurrency) ?: "HKD", json.optBoolean("initialized"), json.optBoolean("separate"), cache,
            lastChecked = if (json.has("lastChecked")) json.getLong("lastChecked") else null)
    }.getOrDefault(FxSnapshot())

    private fun persist(snapshot: FxSnapshot) {
        val json = JSONObject().apply {
            put("currency", snapshot.currency); put("initialized", snapshot.initialized); put("separate", snapshot.separate)
            snapshot.lastChecked?.let { put("lastChecked", it) }
            put("quotes", JSONArray().apply { snapshot.quotes.values.forEach { q -> put(JSONObject().apply {
                put("date", q.date.toString()); put("source", q.source); put("target", q.target); put("rate", q.rate)
                put("rateDate", q.rateDate.toString()); put("fetchedAt", q.fetchedAt)
            }) } })
        }
        check(preferences.edit().putString("state", json.toString()).commit()) { "Could not save currency preferences." }
        mutable.value = snapshot
    }

    suspend fun initialize(entries: List<LedgerEntry>) = withContext(Dispatchers.IO) { mutex.withLock {
        if (!mutable.value.initialized) entries.filter { !it.deleted && it.draft.sourcePackage != null }
            .minWithOrNull(compareBy<LedgerEntry> { it.createdAt }.thenBy { it.draft.occurredAt }.thenBy { it.id })?.let {
                if (it.draft.currency != mutable.value.currency) currencyGeneration++
                persist(mutable.value.copy(currency = it.draft.currency, initialized = true))
            }
    } }

    suspend fun setCurrency(currency: String) = withContext(Dispatchers.IO) { mutex.withLock {
        require(supportedCurrency(currency)); if (currency != mutable.value.currency) currencyGeneration++
        persist(mutable.value.copy(currency = currency, initialized = true, failed = false))
    } }
    suspend fun setSeparate(separate: Boolean) = withContext(Dispatchers.IO) { mutex.withLock { persist(mutable.value.copy(separate = separate)) } }

    /** Daily calls fill missing historical rates and update today's quotes. Manual refresh also rechecks history. */
    suspend fun refresh(entries: List<LedgerEntry>, force: Boolean = false): Boolean = withContext(Dispatchers.IO) { refreshMutex.withLock {
        val (start, generation) = mutex.withLock {
            val start = mutable.value
            mutable.value = start.copy(refreshing = true, failed = false)
            start to currencyGeneration
        }
        val today = Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()).toLocalDate()
        val foreign = entries.filter { !it.deleted && it.draft.currency != start.currency && !(it.draft.exchangeRate != null && it.draft.exchangeRateCurrency == start.currency) }
        val wanted = foreign.groupBy { it.draft.currency }.mapValues { (_, rows) ->
            rows.map { it.draft.occurredAt.atZone(ZoneId.systemDefault()).toLocalDate() }.filter { it <= today }.toSet() + today
        }
        var cache = start.quotes
        var failed = false
        try {
            for ((source, dates) in wanted) {
                val needed = dates.filter { date ->
                    val quote = cache["$date:$source:${start.currency}"]
                    val fetchedDate = quote?.let { Instant.ofEpochMilli(it.fetchedAt).atZone(ZoneId.systemDefault()).toLocalDate() }
                    force || quote == null || (date == today && fetchedDate!! < today) || (date < today && fetchedDate!! <= date)
                }.toSet()
                if (needed.isEmpty()) continue
                try {
                    val received = client.fetch(source, start.currency, needed)
                    cache = cache + received.associateBy { it.key }
                    if (received.map { it.date }.toSet() != needed) failed = true
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true }
            }
            mutex.withLock {
                val current = mutable.value
                val sameCurrency = generation == currencyGeneration
                persist(current.copy(quotes = current.quotes + cache, refreshing = false,
                    lastChecked = if (sameCurrency) clock() else current.lastChecked, failed = sameCurrency && failed))
            }
            !failed
        } catch (cancelled: CancellationException) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { mutex.withLock { mutable.value = mutable.value.copy(refreshing = false) } }
            throw cancelled
        } catch (_: Exception) { mutex.withLock { mutable.value = mutable.value.copy(refreshing = false, failed = generation == currencyGeneration) }; false }
    } }
}
