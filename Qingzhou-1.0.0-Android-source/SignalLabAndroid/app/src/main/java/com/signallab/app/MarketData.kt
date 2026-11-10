package com.signallab.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class Candle(
    val openTime: Long, val open: Double, val high: Double, val low: Double,
    val close: Double, val volume: Double, val takerBuyVolume: Double, val closed: Boolean
)

data class MarketSnapshot(
    val symbol: String, val candles: List<Candle>, val lastPrice: Double,
    val bid: Double, val ask: Double, val lastUpdate: Long
)

object MarketStore {
    private val candles = mutableMapOf("BTCUSDT" to mutableListOf<Candle>(), "ETHUSDT" to mutableListOf<Candle>())
    private val prices = mutableMapOf<String, Double>()
    private val indexPrices = mutableMapOf<String, Double>()
    private val bids = mutableMapOf<String, Double>()
    private val asks = mutableMapOf<String, Double>()
    private val updates = mutableMapOf<String, Long>()
    private val indexUpdates = mutableMapOf<String, Long>()

    @Synchronized fun seed(symbol: String, values: List<Candle>) {
        val list = candles.getOrPut(symbol) { mutableListOf() }
        list.clear(); list.addAll(values.takeLast(200))
        values.lastOrNull()?.let { prices[symbol] = it.close; updates[symbol] = System.currentTimeMillis() }
    }

    @Synchronized fun updateCandle(symbol: String, candle: Candle) {
        val list = candles.getOrPut(symbol) { mutableListOf() }
        val index = list.indexOfFirst { it.openTime == candle.openTime }
        if (index >= 0) list[index] = candle else list.add(candle)
        list.sortBy { it.openTime }
        while (list.size > 200) list.removeAt(0)
        prices[symbol] = candle.close
        updates[symbol] = System.currentTimeMillis()
    }

    @Synchronized fun updateIndexPrice(symbol: String, price: Double) {
        if (price > 0.0) { indexPrices[symbol] = price; indexUpdates[symbol] = System.currentTimeMillis() }
        updates[symbol] = System.currentTimeMillis()
    }

    @Synchronized fun updateBook(symbol: String, bid: Double, ask: Double) {
        bids[symbol] = bid; asks[symbol] = ask; updates[symbol] = System.currentTimeMillis()
    }

    @Synchronized fun snapshot(symbol: String): MarketSnapshot = MarketSnapshot(
        symbol, candles[symbol]?.toList() ?: emptyList(), indexPrices[symbol] ?: (prices[symbol] ?: 0.0),
        bids[symbol] ?: 0.0, asks[symbol] ?: 0.0, indexUpdates[symbol] ?: 0L
    )
}

class BinanceMarketClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    fun fetchHistory(symbol: String): List<Candle> {
        val request = Request.Builder()
            .url("https://fapi.binance.com/fapi/v1/klines?symbol=$symbol&interval=1m&limit=120")
            .get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Binance history HTTP ${response.code}")
            val array = JSONArray(response.body?.string().orEmpty())
            val out = mutableListOf<Candle>()
            for (i in 0 until array.length()) {
                val row = array.getJSONArray(i)
                out += Candle(
                    row.getLong(0), row.getString(1).toDouble(), row.getString(2).toDouble(),
                    row.getString(3).toDouble(), row.getString(4).toDouble(), row.getString(5).toDouble(),
                    row.getString(9).toDouble(), row.getLong(6) < System.currentTimeMillis()
                )
            }
            return out
        }
    }

    fun connect(onFailure: (String) -> Unit): WebSocket {
        val url = "wss://fstream.binance.com/market/stream?streams=btcusdt@kline_1m/ethusdt@kline_1m/btcusdt@markPrice@1s/ethusdt@markPrice@1s/btcusdt@bookTicker/ethusdt@bookTicker"
        val request = Request.Builder().url(url).build()
        return http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root = JSONObject(text)
                    val data = root.optJSONObject("data") ?: root
                    when (data.optString("e")) {
                        "kline" -> {
                            val symbol = data.optString("s")
                            val k = data.getJSONObject("k")
                            val candle = Candle(
                                k.getLong("t"), k.getString("o").toDouble(), k.getString("h").toDouble(),
                                k.getString("l").toDouble(), k.getString("c").toDouble(),
                                k.getString("v").toDouble(), k.optString("V", "0").toDouble(), k.optBoolean("x")
                            )
                            MarketStore.updateCandle(symbol, candle)
                        }
                        "markPriceUpdate" -> {
                            val symbol = data.optString("s")
                            MarketStore.updateIndexPrice(symbol, data.optString("i", "0").toDouble())
                        }
                        "bookTicker" -> {
                            val symbol = data.optString("s")
                            MarketStore.updateBook(symbol, data.optString("b", "0").toDouble(), data.optString("a", "0").toDouble())
                        }
                    }
                } catch (_: Exception) { /* Ignore malformed market frames; next frame will refresh state. */ }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onFailure(t.message ?: "Binance WebSocket disconnected")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onFailure("Binance WebSocket closed: $reason")
            }
        })
    }
}

object TechnicalAnalysis {
    fun buildFeatures(snapshot: MarketSnapshot): JSONObject? {
        val cs = snapshot.candles.filter { it.closed }.takeLast(100)
        if (cs.size < 60 || snapshot.lastPrice <= 0.0) return null
        val closes = cs.map { it.close }
        val ema9 = ema(closes, 9); val ema21 = ema(closes, 21); val ema50 = ema(closes, 50)
        val rsi = rsi(closes, 14)
        val atr = atr(cs, 14)
        val avgVol = cs.takeLast(21).dropLast(1).map { it.volume }.average().coerceAtLeast(1e-12)
        val last = cs.last()
        val ret1 = pct(closes[closes.lastIndex], closes[closes.lastIndex - 1])
        val ret5 = pct(closes.last(), closes[closes.lastIndex - 5])
        val ret15 = pct(closes.last(), closes[closes.lastIndex - 15])
        val spreadPct = if (snapshot.bid > 0 && snapshot.ask > 0) (snapshot.ask - snapshot.bid) / ((snapshot.ask + snapshot.bid) / 2.0) * 100.0 else -1.0
        return JSONObject().apply {
            put("symbol", snapshot.symbol); put("interval", "1m")
            put("last_price", snapshot.lastPrice); put("last_closed_candle_time_ms", last.openTime)
            put("ema_9", ema9); put("ema_21", ema21); put("ema_50", ema50)
            put("ema_trend", when { ema9 > ema21 && ema21 > ema50 -> "BULLISH"; ema9 < ema21 && ema21 < ema50 -> "BEARISH"; else -> "MIXED" })
            put("rsi_14", rsi); put("atr_14", atr); put("atr_percent", atr / snapshot.lastPrice * 100.0)
            put("volume_ratio", last.volume / avgVol); put("taker_buy_volume_ratio", if (last.volume > 0) last.takerBuyVolume / last.volume else 0.0)
            put("return_1m_percent", ret1); put("return_5m_percent", ret5); put("return_15m_percent", ret15)
            put("best_bid", snapshot.bid); put("best_ask", snapshot.ask); put("spread_percent", spreadPct)
            put("sample_candles", cs.takeLast(12).map { "${it.openTime}:${it.open},${it.high},${it.low},${it.close},${it.volume}" })
        }
    }

    private fun pct(a: Double, b: Double) = if (b == 0.0) 0.0 else (a / b - 1.0) * 100.0
    private fun ema(values: List<Double>, period: Int): Double {
        val alpha = 2.0 / (period + 1.0)
        var value = values.take(period).average()
        for (v in values.drop(period)) value = alpha * v + (1.0 - alpha) * value
        return value
    }
    private fun rsi(values: List<Double>, period: Int): Double {
        val changes = values.zipWithNext { a, b -> b - a }.takeLast(period)
        val gains = changes.filter { it > 0 }.sum() / period
        val losses = changes.filter { it < 0 }.sum().let { abs(it) } / period
        if (losses == 0.0) return 100.0
        val rs = gains / losses
        return 100.0 - (100.0 / (1.0 + rs))
    }
    private fun atr(cs: List<Candle>, period: Int): Double {
        val trs = cs.zipWithNext().map { (prev, cur) ->
            maxOf(cur.high - cur.low, abs(cur.high - prev.close), abs(cur.low - prev.close))
        }
        return trs.takeLast(period).average()
    }
}
