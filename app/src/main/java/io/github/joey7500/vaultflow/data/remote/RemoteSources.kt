package io.github.joey7500.vaultflow.data.remote

import android.util.Base64
import io.github.joey7500.vaultflow.domain.fx.CnbRatesParser
import io.github.joey7500.vaultflow.domain.fx.FxRates
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** A user-presentable failure (never contains credentials). */
class RemoteException(message: String) : IOException(message)

object Http {
    /** No logging interceptor on purpose: responses contain balances, requests contain auth headers. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply {
            header("User-Agent", "VaultFlow-Android")
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            response.body?.string() ?: throw RemoteException("Empty response")
        }
    }
}

class HttpStatusException(val code: Int) : IOException("HTTP $code")

// ───────────────────────────── Trading 212 ─────────────────────────────

@Serializable
data class T212Cash(val availableToTrade: Double? = null, val inPies: Double? = null, val reservedForOrders: Double? = null)

@Serializable
data class T212Investments(
    val currentValue: Double? = null,
    val totalCost: Double? = null,
    val unrealizedProfitLoss: Double? = null,
    val realizedProfitLoss: Double? = null,
)

@Serializable
data class T212Summary(
    val id: Long? = null,
    val currency: String? = null,
    val totalValue: Double? = null,
    val cash: T212Cash? = null,
    val investments: T212Investments? = null,
)

/**
 * Trading 212 Public API (v0), read-only use. Auth: HTTP Basic with API key as
 * username and API secret as password. Keys created before the key+secret
 * scheme can be used alone (sent raw in the Authorization header).
 * Rate limit for the summary endpoint: 1 request / 5 s.
 */
object Trading212Client {
    private const val LIVE = "https://live.trading212.com/api/v0"

    suspend fun accountSummary(apiKey: String, apiSecret: String?): T212Summary {
        val auth = if (apiSecret.isNullOrBlank()) {
            apiKey.trim()
        } else {
            "Basic " + Base64.encodeToString("${apiKey.trim()}:${apiSecret.trim()}".toByteArray(), Base64.NO_WRAP)
        }
        val body = try {
            Http.get("$LIVE/equity/account/summary", mapOf("Authorization" to auth))
        } catch (e: HttpStatusException) {
            throw RemoteException(
                when (e.code) {
                    401 -> "Trading 212 rejected the key/secret (401). Re-check them in Settings → Vault."
                    403 -> "Key lacks permission (403). Enable 'Account data' when creating the API key."
                    429 -> "Trading 212 rate limit hit. Try again in a few seconds."
                    else -> "Trading 212 error (HTTP ${e.code})."
                },
            )
        }
        return Http.json.decodeFromString(T212Summary.serializer(), body)
    }
}

// ───────────────────────────── Gold spot ─────────────────────────────

/** Free, key-less XAU/USD spot (xaus.com). Returns USD per troy ounce. */
object GoldPriceClient {
    private const val URL = "https://xaus.com/api/v1/spot"

    suspend fun usdPerOunce(): Double {
        val body = Http.get(URL)
        val root = Http.json.parseToJsonElement(body)
        return findNumber(root, "spot_usd_oz")
            ?: findNumber(root, "price")
            ?: throw RemoteException("Gold price response not understood")
    }

    private fun findNumber(e: JsonElement, key: String): Double? = when (e) {
        is JsonObject -> (e[key] as? JsonPrimitive)?.doubleOrNull ?: e.values.firstNotNullOfOrNull { findNumber(it, key) }
        is JsonArray -> e.firstNotNullOfOrNull { findNumber(it, key) }
        else -> null
    }
}

// ───────────────────────────── ČNB FX ─────────────────────────────

object CnbClient {
    suspend fun dailyRates(): FxRates {
        val text = runCatching { Http.get(CnbRatesParser.URL_EN) }.getOrNull()
            ?: Http.get(CnbRatesParser.URL_CS)
        val rates = CnbRatesParser.parse(text)
        if (rates.czkPerUnit.isEmpty()) throw RemoteException("ČNB rates response not understood")
        return rates
    }
}
