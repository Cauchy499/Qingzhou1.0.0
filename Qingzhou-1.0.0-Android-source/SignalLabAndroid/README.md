# 轻舟 Android 1.0.0（本地模拟盘）

轻舟是一款 Android 原生 BTC / ETH 行情监控与 AI 方向模拟交易 App。项目默认配置：BTCUSDT / ETHUSDT、30 分钟模拟方向单、双模型置信度阈值 80%、每笔 100 USDT、模拟本金 10,000 USDT。所有参数均可在 App 内调整。

## 版本信息

- App 名称：轻舟
- 版本名称：1.0.0
- 版本号：100
- Android applicationId：`com.signallab.app`（保留包名，便于后续覆盖升级）

## 功能

- Binance USDⓈ-M Futures 公共 REST + WebSocket K线/盘口，并从 Mark Price Stream 读取 Index Price，不需要 Binance API Key。
- DeepSeek 双模型：`deepseek-flash` 初筛，`deepseek-v4-pro` 对候选信号复核。
- 两模型方向一致、各自自评置信度均达到阈值、Pro 风险等级不是 HIGH，并通过本地行情过滤，才自动模拟开仓。
- 持仓到期后按最新可用 Binance Futures Mark Price Stream 中的 Index Price 参考值自动结算。
- 本地 SQLite 保存信号和模拟单；胜率只按已结算的 WIN/LOSS 统计，平局不计入分母。
- API Key 使用 Android Keystore AES-GCM 加密后保存在本机；不写入源码，不同步到自建云端。
- Android 前台服务持续显示通知；用户可随时停止。
- 新增后台权限引导：监测未启动时展示权限状态，首次发现通知/电池优化设置未就绪时弹出引导；启动前再次检查通知权限，并提示关闭电池优化。
- 根据手机厂商显示后台自启动/后台活动设置提示，支持跳转 Android 应用详情、通知设置和电池优化豁免页面。

## 权限与后台运行说明

- 网络权限由 Android 自动授予；前台服务权限由 Manifest 声明，不需要用户在普通权限弹窗中手动授予。
- Android 13 及以上需要用户允许通知权限，轻舟才能可靠显示监控状态和 AI 信号通知；未允许时不会启动监控。
- Android 6.0 及以上可通过系统电池优化豁免页面允许轻舟不受 Doze 电池优化限制。此项是持续后台监控的建议设置，用户可选择暂时跳过。
- 小米/Redmi/POCO、OPPO/一加/realme、vivo/iQOO、华为/荣耀、三星等品牌可能还有自启动或后台活动管理。Android 没有统一的跨厂商自启动授权 API，因此轻舟会提供品牌提示，用户需要按手机系统菜单手动开启。
- 允许电池优化豁免不能保证后台永不停止。强制停止、关机、断网、厂商后台管理、系统回收等仍可能中断服务。
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 仅用于用户明确开启持续监控时请求系统豁免。若未来提交 Google Play，需核对当时的 Play 政策和豁免资格；本项目当前以用户自行安装/测试为目标。

## 架构和边界

- 不需要自建服务器，但 DeepSeek API 和 Binance 行情都需要手机联网。DeepSeek API 会按账户计费。
- 这是“方向模拟盘”，以 1 倍名义金额按 Binance Index Price 的价格百分比变化计算模拟盈亏；不是币安事件合约的官方派彩/结算规则复制品，也不会连接或操作真实交易账户。
- DeepSeek 返回的 `confidence` 是模型自评，不是经过验证的真实概率。80% 仅是筛选阈值，不能保证 80% 胜率。上线前应先长时间模拟并检验校准结果。
- 当前策略是保守的 MVP：需要 Flash/Pro 同方向且均过阈值，并要求 EMA 趋势一致、行情新鲜、盘口价差/ATR/成交量过滤通过。过滤会导致信号较少，这是刻意的风险控制。
- API Key 在本机加密存储仍不能防止已 root 设备或被高度逆向分析的设备完全提取。不要把有高额度权限的 Key 放进公开 APK。

## 用 Android Studio 构建 APK

1. 在电脑安装 Android Studio（建议最新版）和 Android SDK Platform 35。
2. 解压项目，Android Studio 选择 **Open**，打开项目根目录 `SignalLabAndroid`。
3. 等待 Gradle Sync 完成。项目使用 Android Gradle Plugin 8.7.3、Gradle 8.9、Kotlin 2.0.21、JDK 17。
4. 连接 Android 手机，开启开发者选项和 USB 调试，点击 Run；或选择 **Build > Build APK(s)**。
5. Debug APK 通常输出在 `app/build/outputs/apk/debug/app-debug.apk`。
6. 安装后输入 DeepSeek API Key，保存设置，按 App 首页的权限引导允许通知，并按建议关闭电池优化限制。
7. 根据手机品牌提示，在系统设置中允许轻舟自启动/后台活动（如该品牌提供此类设置），然后点击“启动后台监控”。

## 首次使用建议

1. 先用小额模拟金额（例如 10 USDT）运行至少 1-2 周。
2. 核对信号、开仓时间、到期结算价格和统计记录。
3. 重点观察不同置信度区间的实际胜率，而不是只看模型自报 confidence。
4. 如遇 API 错误、行情延迟、数据缺失或双模型分歧，程序按设计不自动开仓。

## 主要配置

- DeepSeek API base URL：`https://api.deepseek.com`
- Chat Completions：`POST /chat/completions`
- Flash：`deepseek-flash`
- Pro：`deepseek-v4-pro`
- Binance Futures WebSocket：`wss://fstream.binance.com/market/stream`
- Binance Futures REST Klines：`https://fapi.binance.com/fapi/v1/klines`
- 参考价：`markPriceUpdate.i`（Index Price）

## 在线构建 APK（无需本机安装 Android Studio）

项目已附带 GitHub Actions 工作流：`.github/workflows/build-apk.yml`。将整个项目上传到你自己的 GitHub 仓库后：

1. 打开仓库的 **Actions** 页面。
2. 选择 **Build Qingzhou APK**。
3. 点击 **Run workflow**（或推送代码后自动触发）。
4. 构建成功后打开该次运行记录，在 **Artifacts** 下载 `qingzhou-1.0.0-debug-apk`。
5. 解压得到 `app-debug.apk`，传到 Android 手机安装。

GitHub Actions 只用于编译 APK，不是 App 的运行服务器；安装后行情、AI 调用和模拟交易仍由手机直接运行。构建需要 GitHub runner 联网下载 Gradle 与 Android SDK。

## 当前构建状态

本交付环境没有 Android SDK，且无法解析 `services.gradle.org` 下载 Gradle 8.9，因此本次不能在这里生成或验证 APK。已加入 GitHub Actions 自动构建工作流，需在有 Android SDK/网络的 GitHub runner 或 Android Studio 环境完成编译。APK 编译成功后仍需在真实 Android 设备测试通知权限、后台服务、不同厂商省电策略、Binance WebSocket 和 DeepSeek API。
