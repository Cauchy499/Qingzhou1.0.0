package com.signallab.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var marketView: TextView
    private lateinit var statsView: TextView
    private lateinit var tradesView: TextView
    private lateinit var signalsView: TextView
    private lateinit var permissionStatusView: TextView
    private lateinit var permissionDetailView: TextView
    private lateinit var permissionGuideButton: Button
    private var initialPermissionGuideShown = false
    private lateinit var keyInput: EditText
    private lateinit var stakeInput: EditText
    private lateinit var capitalInput: EditText
    private lateinit var thresholdInput: EditText
    private lateinit var durationInput: EditText
    private lateinit var autoSwitch: Switch
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshDashboard()
            updatePermissionPanel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadSettings()
    }

    override fun onResume() {
        super.onResume()
        if (!receiverRegistered) {
            val filter = IntentFilter(MonitorService.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else { registerReceiver(receiver, filter) }
            receiverRegistered = true
        }
        refreshDashboard()
        updatePermissionPanel()
        if (!initialPermissionGuideShown && !isMonitoringServiceRunning() && (!notificationsGranted() || !batteryOptimizationDisabled())) {
            initialPermissionGuideShown = true
            showPermissionGuideDialog()
        }
    }

    override fun onPause() {
        if (receiverRegistered) {
            unregisterReceiver(receiver); receiverRegistered = false
        }
        super.onPause()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(30))
            setBackgroundColor(0xFFF4F6FA.toInt())
        }
        scroll.addView(root)

        root.addView(text("轻舟", 28, true, 0xFF101827.toInt()))
        root.addView(text("版本 1.0.0 · BTC / ETH · DeepSeek 双模型 · 本地模拟盘", 14, false, 0xFF64748B.toInt()))
        root.addView(gap(16))
        root.addView(section("运行状态"))
        statusView = text("监控未启动", 15, true, 0xFF334155.toInt())
        root.addView(card(statusView))

        root.addView(gap(12))
        root.addView(section("后台权限与运行引导"))
        permissionStatusView = text("正在检查权限…", 15, true, 0xFF334155.toInt())
        root.addView(card(permissionStatusView))
        permissionDetailView = text("轻舟会检查通知权限和后台省电限制。", 13, false, 0xFF64748B.toInt())
        root.addView(card(permissionDetailView))
        permissionGuideButton = button("检查并开启所需权限") { showPermissionGuideDialog() }
        root.addView(permissionGuideButton)

        root.addView(gap(12))
        root.addView(section("实时行情"))
        marketView = text("BTCUSDT：--\nETHUSDT：--", 16, true, 0xFF0F172A.toInt())
        root.addView(card(marketView))

        root.addView(gap(12))
        root.addView(section("模拟盘统计"))
        statsView = text("尚无结算记录", 15, false, 0xFF334155.toInt())
        root.addView(card(statsView))

        root.addView(gap(12))
        root.addView(section("自动模拟设置"))
        autoSwitch = Switch(this).apply { text = "自动模拟开仓（B模式）"; textSize = 15f; isChecked = true }
        root.addView(card(autoSwitch))
        stakeInput = edit("每笔模拟金额（USDT）", "100", number = true)
        capitalInput = edit("模拟本金（USDT）", "10000", number = true)
        thresholdInput = edit("双模型最低置信度（%）", "80", number = true)
        durationInput = edit("模拟持仓时间（分钟）", "30", number = true)
        root.addView(stakeInput); root.addView(capitalInput); root.addView(thresholdInput); root.addView(durationInput)

        root.addView(gap(12))
        root.addView(section("DeepSeek API 配置"))
        keyInput = EditText(this).apply {
            hint = "粘贴 DeepSeek API Key（只保存在本机）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = 14f; setSingleLine(true); setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        root.addView(keyInput, matchWrap())
        root.addView(text("Flash 主筛选：deepseek-flash\nPro 二次复核：deepseek-v4-pro\nAPI Key 使用 Android Keystore 加密保存；不会上传到自建服务器。", 12, false, 0xFF64748B.toInt()))
        val saveButton = button("保存设置和 API Key") { saveSettings(showToast = true) }
        root.addView(saveButton)

        root.addView(gap(8))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; weightSum = 2f }
        val start = button("启动后台监控") { startMonitoring() }
        val stop = button("停止监控") { stopMonitoring() }
        buttons.addView(start, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        buttons.addView(stop, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        root.addView(buttons)

        root.addView(gap(12))
        root.addView(section("当前模拟持仓 / 最近记录"))
        tradesView = text("暂无模拟交易", 13, false, 0xFF334155.toInt())
        root.addView(card(tradesView))
        root.addView(gap(10))
        root.addView(section("最近 AI 判断（含未开仓信号）"))
        signalsView = text("暂无 AI 判断记录", 12, false, 0xFF334155.toInt())
        root.addView(card(signalsView))
        root.addView(gap(12))
        root.addView(text("重要说明：本 App 只做模拟方向交易，不会连接币安账户或下真实订单。模拟盈亏按1倍名义金额的价格变动计算，不等同于币安事件合约的实际派彩规则。DeepSeek 自评置信度不是已验证的真实胜率。后台运行仍受 Android 省电、系统回收、网络中断影响。", 12, false, 0xFF92400E.toInt()))
        setContentView(scroll)
    }

    private fun loadSettings() {
        val p = getSharedPreferences("app_settings", MODE_PRIVATE)
        stakeInput.setText(p.getString("stake", "100"))
        capitalInput.setText(p.getString("capital", "10000"))
        thresholdInput.setText(p.getInt("threshold", 80).toString())
        durationInput.setText(p.getInt("duration", 30).toString())
        autoSwitch.isChecked = p.getBoolean("auto_trade", true)
        keyInput.hint = if (KeyVault(this).hasApiKey()) "API Key 已加密保存；输入新 Key 可替换" else "粘贴 DeepSeek API Key（只保存在本机）"
    }

    private fun saveSettings(showToast: Boolean): Boolean {
        val stake = stakeInput.text.toString().toDoubleOrNull()
        val capital = capitalInput.text.toString().toDoubleOrNull()
        val threshold = thresholdInput.text.toString().toIntOrNull()
        val duration = durationInput.text.toString().toIntOrNull()
        if (stake == null || stake <= 0 || capital == null || capital <= 0 || (stake != null && capital != null && stake > capital) || threshold == null || threshold !in 80..99 || duration == null || duration !in 5..240) {
            toast("请检查金额（单笔不能超过本金）、置信度（80-99）和持仓时间（5-240分钟）")
            return false
        }
        getSharedPreferences("app_settings", MODE_PRIVATE).edit()
            .putString("stake", stake.toString()).putString("capital", capital.toString())
            .putInt("threshold", threshold).putInt("duration", duration)
            .putBoolean("auto_trade", autoSwitch.isChecked).apply()
        val key = keyInput.text.toString().trim()
        if (key.isNotBlank()) {
            try { KeyVault(this).saveApiKey(key); keyInput.setText(""); keyInput.hint = "API Key 已加密保存；输入新 Key 可替换" }
            catch (e: Exception) { toast("保存 API Key 失败：${e.message}"); return false }
        }
        if (showToast) toast("设置已保存到手机")
        return true
    }

    private fun startMonitoring() {
        if (!saveSettings(showToast = false)) return
        if (!KeyVault(this).hasApiKey()) { toast("请先输入 DeepSeek API Key"); return }
        if (!notificationsGranted()) {
            requestNotificationPermissionOrSettings()
            toast("请先开启轻舟的通知权限，才能接收监控状态和 AI 信号")
            updatePermissionPanel()
            return
        }
        if (!batteryOptimizationDisabled()) {
            AlertDialog.Builder(this)
                .setTitle("建议允许轻舟在后台运行")
                .setMessage("关闭电池优化可以降低 Android 在后台暂停行情连接的概率。你也可以暂时继续启动，但部分手机可能会限制后台监测。")
                .setPositiveButton("去开启") { _, _ -> requestBatteryOptimizationExemption() }
                .setNegativeButton("暂时继续") { _, _ -> launchMonitorService() }
                .show()
            return
        }
        launchMonitorService()
    }

    private fun launchMonitorService() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java))
            toast("已请求启动轻舟后台监测")
        } catch (e: Exception) { toast("启动失败：${e.message}") }
        updatePermissionPanel()
    }

    private fun stopMonitoring() {
        stopService(Intent(this, MonitorService::class.java))
        toast("已发送停止请求")
        refreshDashboard()
        updatePermissionPanel()
    }

    private fun notificationsGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED && NotificationManagerCompat.from(this).areNotificationsEnabled()
        } else NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    private fun batteryOptimizationDisabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun isMonitoringServiceRunning(): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == MonitorService::class.java.name }
    }

    private fun updatePermissionPanel() {
        if (!::permissionStatusView.isInitialized) return
        val active = isMonitoringServiceRunning()
        val notifications = notificationsGranted()
        val battery = batteryOptimizationDisabled()
        permissionStatusView.text = when {
            active -> "后台监测服务正在运行"
            !notifications -> "监测尚未开启：需要开启通知权限"
            !battery -> "监测尚未开启：建议关闭电池优化限制"
            else -> "必要权限已就绪，可以启动后台监测"
        }
        permissionDetailView.text = buildString {
            append(if (notifications) "✓ 通知权限已开启" else "✗ 通知权限未开启")
            append("\n")
            append(if (battery) "✓ 电池优化限制已豁免" else "! 电池优化仍可能限制后台运行")
            append("\n")
            append("✓ 网络权限由 Android 自动授予；前台服务权限由系统清单声明。")
            append("\n")
            append(manufacturerBackgroundHint())
        }
        permissionGuideButton.text = if (active) "查看后台权限设置" else "检查并开启所需权限"
    }

    private fun showPermissionGuideDialog() {
        val notifications = notificationsGranted()
        val battery = batteryOptimizationDisabled()
        val message = buildString {
            append(if (notifications) "✓ 通知权限：已开启" else "✗ 通知权限：未开启（必须开启，才能可靠接收信号通知）")
            append("\n\n")
            append(if (battery) "✓ 电池优化：已豁免" else "! 电池优化：尚未豁免（建议开启，减少后台断连）")
            append("\n\n")
            append("后台监控使用 Android 前台服务，并显示持续通知。部分品牌还需要在系统设置中手动允许自启动、后台活动或将轻舟加入最近任务锁定。")
            append("\n\n")
            append(manufacturerBackgroundHint())
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("轻舟后台监测权限引导")
            .setMessage(message)
            .setNegativeButton("稍后") { _, _ -> updatePermissionPanel() }
            .setNeutralButton("查看手机设置指引") { _, _ -> showManufacturerGuide() }
        when {
            !notifications -> builder.setPositiveButton("开启通知权限") { _, _ -> requestNotificationPermissionOrSettings() }
            !battery -> builder.setPositiveButton("允许后台运行") { _, _ -> requestBatteryOptimizationExemption() }
            else -> builder.setPositiveButton("打开轻舟系统设置") { _, _ -> openAppSettings() }
        }
        builder.show()
    }

    private fun requestNotificationPermissionOrSettings() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7001)
        } else {
            try {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                startActivity(intent)
            } catch (_: Exception) { openAppSettings() }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !batteryOptimizationDisabled()) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) { openAppSettings() }
        } else openAppSettings()
    }

    private fun openAppSettings() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { toast("请在系统设置中找到应用：轻舟") }
    }

    private fun manufacturerBackgroundHint(): String {
        return when (Build.MANUFACTURER.lowercase(Locale.ROOT)) {
            "xiaomi", "redmi", "poco" -> "小米/Redmi/POCO：建议开启自启动，将省电策略设为无限制，并允许后台运行。"
            "oppo", "oneplus", "realme" -> "OPPO/一加/realme：建议允许自启动和后台活动，并将电池使用设为不限制。"
            "vivo", "iqoo" -> "vivo/iQOO：建议允许自启动和后台高耗电运行，并在最近任务中锁定轻舟。"
            "huawei", "honor" -> "华为/荣耀：建议在应用启动管理中改为手动管理，并允许自启动、关联启动和后台活动。"
            "samsung" -> "三星：建议将轻舟加入电池设置中的‘从不休眠应用’，并允许后台使用。"
            else -> "建议在系统设置的应用电池/后台活动页面，将轻舟设为允许后台运行或不受限制。"
        }
    }

    private fun showManufacturerGuide() {
        AlertDialog.Builder(this)
            .setTitle("手机品牌后台设置指引")
            .setMessage(manufacturerBackgroundHint() + "\n\n不同 Android 版本的菜单名称可能不同。轻舟无法代替你修改厂商自定义的自启动设置，请按系统页面提示手动开启。")
            .setPositiveButton("打开轻舟应用设置") { _, _ -> openAppSettings() }
            .setNegativeButton("知道了", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7001) {
            updatePermissionPanel()
            if (notificationsGranted()) {
                toast("通知权限已开启，可以启动轻舟监测")
                if (!batteryOptimizationDisabled()) showPermissionGuideDialog()
            } else toast("通知权限未开启，后台监测暂不能启动")
        }
    }

    private fun refreshDashboard() {
        if (!::statusView.isInitialized) return
        val runtime = getSharedPreferences("runtime", MODE_PRIVATE)
        statusView.text = runtime.getString("status", "监控未启动") ?: "监控未启动"
        val btc = MarketStore.snapshot("BTCUSDT").lastPrice
        val eth = MarketStore.snapshot("ETHUSDT").lastPrice
        marketView.text = "BTCUSDT：${price(btc)}\nETHUSDT：${price(eth)}"
        val db = TradeDb(this)
        val s = db.stats()
        val capital = getSharedPreferences("app_settings", MODE_PRIVATE).getString("capital", "10000")?.toDoubleOrNull() ?: 10000.0
        statsView.text = "模拟本金：${String.format(Locale.US, "%.2f", capital)} USDT    当前余额：${String.format(Locale.US, "%.4f", capital + s.pnl)} USDT\n已结算：${s.total} 笔    胜：${s.wins}    负：${s.losses}    平：${s.pushes}\n胜率：${String.format(Locale.US, "%.1f", s.winRate)}%    模拟盈亏：${String.format(Locale.US, "%+.4f", s.pnl)} USDT"
        val open = db.openTrades()
        val recent = db.recentTrades(8)
        val lines = mutableListOf<String>()
        if (open.isNotEmpty()) {
            lines += "【持仓中】"
            open.forEach { lines += "${it.symbol} ${it.direction} · 入场 ${price(it.entryPrice)} · ${remaining(it.dueAt)}后结算" }
        }
        if (recent.isNotEmpty()) {
            lines += "【最近模拟单】"
            recent.forEach { t ->
                val date = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(t.openedAt))
                lines += "$date ${t.symbol} ${t.direction} · ${t.result} · ${t.pnl?.let { String.format(Locale.US, "%+.4f", it) } ?: "未结算"} USDT"
            }
        }
        tradesView.text = if (lines.isEmpty()) "暂无模拟交易" else lines.joinToString("\n")
        val signalLines = db.recentSignals(10).map { row ->
            val p = row.split("|", limit = 7)
            if (p.size >= 7) {
                val date = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(p[0].toLongOrNull() ?: 0L))
                "$date ${p[1]} ${p[2]} · Flash ${p[3]}% / Pro ${p[4]}% · ${p[5]}\n${p[6]}"
            } else row
        }
        signalsView.text = if (signalLines.isEmpty()) "暂无 AI 判断记录" else signalLines.joinToString("\n\n")
        db.close()
    }

    private fun remaining(dueAt: Long): String {
        val sec = ((dueAt - System.currentTimeMillis()).coerceAtLeast(0) / 1000)
        return "${sec / 60}分${sec % 60}秒"
    }
    private fun price(v: Double): String = if (v <= 0) "等待行情" else String.format(Locale.US, "%,.2f", v)

    private fun edit(hintText: String, value: String, number: Boolean): EditText = EditText(this).apply {
        hint = hintText; setText(value); textSize = 14f; setSingleLine(true)
        inputType = if (number) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT
        setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(0xFFFFFFFF.toInt())
        layoutParams = matchWrap().apply { topMargin = dp(5); bottomMargin = dp(5) }
    }

    private fun text(value: String, size: Int, bold: Boolean, color: Int) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(3), 0, dp(3))
    }
    private fun section(value: String) = text(value, 16, true, 0xFF1E293B.toInt())
    private fun card(child: TextView) = LinearLayout(this).apply {
        setPadding(dp(14), dp(12), dp(14), dp(12)); setBackgroundColor(0xFFFFFFFF.toInt()); addView(child)
        elevation = dp(1).toFloat()
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 13f; setOnClickListener { action() }
    }
    private fun gap(size: Int) = android.view.View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(size)) }
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
}
