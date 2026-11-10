package com.signallab.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AiDecision(val direction: String, val confidence: Int, val reason: String, val risk: String)

class DeepSeekClient(private val apiKey: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun analyze(model: String, symbol: String, features: JSONObject): AiDecision {
        val system = """你是一个谨慎的加密资产短周期行情分析助手。你只能分析用户给出的公开市场数据，不得声称能保证盈利。请判断未来30分钟相对当前价格的方向。数据可能不完整或有噪声；证据不足必须返回WAIT。confidence是模型自评的0到100分，不是经过统计校准的真实胜率。不要为了达到阈值而虚报概率。只输出JSON对象，字段为 direction(LONG/SHORT/WAIT), confidence(整数0-100), reason(不超过100字), risk_level(LOW/MEDIUM/HIGH)。"""
        val user = """请分析 $symbol 未来30分钟方向。以下是已计算的市场数据JSON：\n${features}\n请只输出JSON，例如：{\"direction\":\"WAIT\",\"confidence\":55,\"reason\":\"信号不一致\",\"risk_level\":\"HIGH\"}。"""
        val body = JSONObject().apply {
            put("model", model)
            put("messages", org.json.JSONArray().put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", user)))
            put("response_format", JSONObject().put("type", "json_object"))
            put("max_tokens", 700)
            if (model == "deepseek-flash") put("thinking", JSONObject().put("type", "disabled"))
            else put("thinking", JSONObject().put("type", "enabled"))
        }
        val request = Request.Builder()
            .url("https://api.deepseek.com/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("DeepSeek $model HTTP ${response.code}: ${raw.take(240)}")
            val content = JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").trim()
            if (content.isBlank()) throw IllegalStateException("DeepSeek $model 返回空内容")
            val json = JSONObject(content)
            val directionRaw = json.optString("direction", "WAIT").uppercase()
            val direction = when (directionRaw) {
                "LONG", "BUY", "UP", "看涨" -> "LONG"
                "SHORT", "SELL", "DOWN", "看跌" -> "SHORT"
                else -> "WAIT"
            }
            val confidence = json.optInt("confidence", 0).coerceIn(0, 100)
            val reason = json.optString("reason", "未提供分析理由").take(180)
            val risk = json.optString("risk_level", "HIGH").uppercase()
            return AiDecision(direction, confidence, reason, risk)
        }
    }
}
