package com.signallab.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import okhttp3.WebSocket
import org.json.JSONObject

class MonitorService : Service() {
    private lateinit var db: TradeDb
    private lateinit var vault: KeyVault
    private lateinit var scheduler: ScheduledExecutorService
    private var socket: WebSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var reconnecting = false
    @Volatile private var stopped = false
    @Volatile private var loopScheduled = false
    private val lastAnalyzed = mutableMapOf<String, Long>()
    private val market = BinanceMarketClient()

    override fun onCreate() {
        super.onCreate()
        db = TradeDb(this)
        vault = KeyVault(this)
        createChannels()
        scheduler = Executors.newScheduledThreadPool(2)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.signallab.app:MarketMonitor").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 60 * 1000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = serviceNotification("正在启动行情监控…")
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, SERVICE_NOTIFICATION_ID, notification, type)
        if (!vault.hasApiKey()) {
            notifyUser("需要配置 DeepSeek API Key", "请打开 App 设置 API Key 后再启动监控。")
            stopSelf()
            return START_NOT_STICKY
        }
        stopped = false
        if (!loopScheduled) {
            loopScheduled = true
            scheduler.execute { bootstrap() }
            scheduler.scheduleAtFixedRate({ safeTick() }, 15, 10, TimeUnit.SECONDS)
        }
        return START_STICKY
    }

    private fun bootstrap() {
        try {
            listOf("BTCUSDT", "ETHUSDT").forEach { symbol ->
                try { MarketStore.seed(symbol, market.fetchHistory(symbol)) }
                catch (e: Exception) { updateStatus("获取 $symbol 历史K线失败：${e.message}") }
            }
            connectSocket()
            updateStatus("监控运行中 · 等待新的一分钟K线")
        } catch (e: Exception) {
            updateStatus("初始化失败：${e.message}")
            scheduleReconnect()
        }
    }

    @Synchronized private fun connectSocket() {
        if (stopped || socket != null || reconnecting) return
        reconnecting = true
        try {
            socket = market.connect { message ->
                socket = null
                updateStatus(message)
                scheduleReconnect()
            }
        } catch (e: Exception) {
            socket = null
            updateStatus("行情连接失败：${e.message}")
            scheduleReconnect()
        } finally { reconnecting = false }
    }

    private fun scheduleReconnect() {
        if (stopped) return
        try { scheduler.schedule({ connectSocket() }, 8, TimeUnit.SECONDS) } catch (_: Exception) { }
    }

    private fun safeTick() {
        if (stopped) return
        try {
            settleDueTrades()
            val apiKey = vault.getApiKey() ?: return
            for (symbol in listOf("BTCUSDT", "ETHUSDT")) {
                val snapshot = MarketStore.snapshot(symbol)
                val closed = snapshot.candles.filter { it.closed }.lastOrNull() ?: continue
                if (closed.openTime <= (lastAnalyzed[symbol] ?: 0L)) continue
                if (snapshot.candles.count { it.closed } < 60) continue
                lastAnalyzed[symbol] = closed.openTime
                analyzeSymbol(apiKey, snapshot)
            }
            val btc = MarketStore.snapshot("BTCUSDT").lastPrice
            val eth = MarketStore.snapshot("ETHUSDT").lastPrice
            updateStatus("监控中 · BTC ${fmt(btc)} · ETH ${fmt(eth)}")
        } catch (e: Exception) {
            updateStatus("监控异常：${e.message ?: "未知错误"}")
        }
    }

    private fun analyzeSymbol(apiKey: String, snapshot: MarketSnapshot) {
        val features = TechnicalAnalysis.buildFeatures(snapshot) ?: return
        val threshold = getSharedPreferences("app_settings", MODE_PRIVATE).getInt("threshold", 80)
        val client = DeepSeekClient(apiKey)
        val flash = try { client.analyze("deepseek-flash", snapshot.symbol, features) }
        catch (e: Exception) {
            db.addSignal(snapshot.symbol, "WAIT", 0, 0, "API_ERROR", "Flash调用失败：${e.message ?: "网络错误"}")
            updateStatus("${snapshot.symbol} Flash 调用失败")
            return
        }
        var pro = AiDecision("WAIT", 0, "Flash未达到复核条件", "HIGH")
        var decision = "WAIT_FLASH"
        if (flash.direction != "WAIT" && flash.confidence >= threshold) {
            pro = try { client.analyze("deepseek-v4-pro", snapshot.symbol, features) }
            catch (e: Exception) {
                db.addSignal(snapshot.symbol, flash.direction, flash.confidence, 0, "PRO_ERROR", "Pro复核失败：${e.message ?: "网络错误"}")
                updateStatus("${snapshot.symbol} Pro 复核失败，未开仓")
                return
            }
            decision = if (pro.direction == flash.direction && pro.confidence >= threshold && pro.risk in setOf("LOW", "MEDIUM")) "CANDIDATE" else "MODEL_DISAGREEMENT"
        }
        var reason = "Flash ${flash.direction} ${flash.confidence}%; Pro ${pro.direction} ${pro.confidence}%; ${flash.reason}; ${pro.reason}"
        if (decision == "CANDIDATE") {
            val guard = localGuard(snapshot, features, flash.direction)
            if (!guard.first) { decision = "LOCAL_FILTER"; reason += "; 本地过滤：${guard.second}" }
            else decision = "APPROVED"
        }
        db.addSignal(snapshot.symbol, flash.direction, flash.confidence, pro.confidence, decision, reason)
        if (decision != "APPROVED") return

        val settings = getSharedPreferences("app_settings", MODE_PRIVATE)
        val auto = settings.getBoolean("auto_trade", true)
        if (!auto) {
            notifyUser("AI信号：${snapshot.symbol}", "${flash.direction} · Flash ${flash.confidence}% / Pro ${pro.confidence}% · 自动模拟已暂停")
            return
        }
        if (db.hasOpenTrade(snapshot.symbol)) return
        val stake = settings.getString("stake", "100")?.toDoubleOrNull()?.coerceIn(1.0, 100000.0) ?: 100.0
        val capital = settings.getString("capital", "10000")?.toDoubleOrNull() ?: 10000.0
        val available = capital + db.stats().pnl - db.openTrades().sumOf { it.stake }
        if (available < stake) {
            db.addSignal(snapshot.symbol, flash.direction, flash.confidence, pro.confidence, "NO_BALANCE", "可用模拟余额不足，未开仓")
            notifyUser("模拟账户余额不足", "${snapshot.symbol} 信号未开仓；可用余额 ${String.format("%.2f", available)} USDT")
            return
        }
        val duration = settings.getInt("duration", 30).coerceIn(5, 240)
        val id = db.openTrade(snapshot.symbol, flash.direction, snapshot.lastPrice, stake,
            System.currentTimeMillis() + duration * 60_000L, flash.confidence, pro.confidence, reason)
        if (id > 0) {
            notifyUser("已自动模拟开仓：${snapshot.symbol}", "方向：${if (flash.direction == "LONG") "看涨" else "看跌"} · 入场价 ${fmt(snapshot.lastPrice)} · ${duration}分钟 · 双模型均≥${threshold}%")
            broadcastUpdate()
        }
    }

    private fun localGuard(snapshot: MarketSnapshot, features: JSONObject, direction: String): Pair<Boolean, String> {
        if (System.currentTimeMillis() - snapshot.lastUpdate > 90_000L) return false to "行情过期"
        val atrPct = features.optDouble("atr_percent", 99.0)
        if (atrPct > 1.8 || atrPct <= 0.0) return false to "波动率异常"
        val volumeRatio = features.optDouble("volume_ratio", 0.0)
        if (volumeRatio < 0.55) return false to "成交量不足"
        val spread = features.optDouble("spread_percent", -1.0)
        if (spread < 0.0) return false to "盘口数据尚未就绪"
        if (spread > 0.08) return false to "盘口价差过大"
        val trend = features.optString("ema_trend", "MIXED")
        if (direction == "LONG" && trend != "BULLISH") return false to "均线趋势未确认"
        if (direction == "SHORT" && trend != "BEARISH") return false to "均线趋势未确认"
        val rsi = features.optDouble("rsi_14", 50.0)
        if (direction == "LONG" && rsi >= 76.0) return false to "RSI过热"
        if (direction == "SHORT" && rsi <= 24.0) return false to "RSI过冷"
        return true to "通过"
    }

    private fun settleDueTrades() {
        val now = System.currentTimeMillis()
        for (trade in db.dueOpenTrades(now)) {
            val snap = MarketStore.snapshot(trade.symbol)
            if (snap.lastPrice <= 0.0 || now - snap.lastUpdate > 90_000L) continue
            val move = (snap.lastPrice / trade.entryPrice) - 1.0
            val signed = if (trade.direction == "LONG") move else -move
            val pnl = trade.stake * signed // 1x directional paper P&L; not Binance event-contract payout.
            val result = when { abs(signed) < 0.0000001 -> "PUSH"; signed > 0 -> "WIN"; else -> "LOSS" }
            db.settleTrade(trade.id, snap.lastPrice, pnl, result)
            notifyUser("模拟单已结算：${trade.symbol}", "${trade.direction} · ${result} · P&L ${if (pnl >= 0) "+" else ""}${String.format("%.4f", pnl)} USDT")
            broadcastUpdate()
        }
    }

    private fun createChannels() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_MONITOR, "后台行情监控", NotificationManager.IMPORTANCE_LOW).apply { description = "行情监控运行状态" })
            manager.createNotificationChannel(NotificationChannel(CHANNEL_SIGNALS, "AI信号与模拟交易", NotificationManager.IMPORTANCE_HIGH).apply { description = "双模型通过复核后的模拟交易通知" })
        }
    }

    private fun serviceNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("轻舟 · 后台监控")
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .build()
    }

    private fun updateStatus(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(SERVICE_NOTIFICATION_ID, serviceNotification(text))
        getSharedPreferences("runtime", MODE_PRIVATE).edit().putString("status", text).putLong("status_time", System.currentTimeMillis()).apply()
        broadcastUpdate()
    }

    private fun notifyUser(title: String, text: String) {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL_SIGNALS)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
    }

    private fun broadcastUpdate() { sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName)) }
    private fun fmt(value: Double): String = if (value <= 0.0) "--" else String.format("%,.2f", value)

    override fun onDestroy() {
        stopped = true
        try { socket?.close(1000, "service stopped") } catch (_: Exception) { }
        socket = null
        if (::scheduler.isInitialized) scheduler.shutdownNow()
        loopScheduled = false
        try { wakeLock?.release() } catch (_: Exception) { }
        wakeLock = null
        getSharedPreferences("runtime", MODE_PRIVATE).edit().putString("status", "监控已停止").apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STATE_CHANGED = "com.signallab.app.STATE_CHANGED"
        private const val SERVICE_NOTIFICATION_ID = 4101
        private const val CHANNEL_MONITOR = "monitor_channel"
        private const val CHANNEL_SIGNALS = "signals_channel"
    }
}
