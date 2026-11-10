package com.signallab.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class PaperTrade(
    val id: Long,
    val symbol: String,
    val direction: String,
    val entryPrice: Double,
    val stake: Double,
    val openedAt: Long,
    val dueAt: Long,
    val exitPrice: Double?,
    val pnl: Double?,
    val result: String,
    val flashConfidence: Int,
    val proConfidence: Int,
    val reason: String
)

data class TradeStats(val total: Int, val wins: Int, val losses: Int, val pushes: Int, val winRate: Double, val pnl: Double)

class TradeDb(context: Context) : SQLiteOpenHelper(context, "signallab.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE trades (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            symbol TEXT NOT NULL, direction TEXT NOT NULL, entry_price REAL NOT NULL,
            stake REAL NOT NULL, opened_at INTEGER NOT NULL, due_at INTEGER NOT NULL,
            exit_price REAL, pnl REAL, result TEXT NOT NULL DEFAULT 'OPEN',
            flash_confidence INTEGER NOT NULL, pro_confidence INTEGER NOT NULL, reason TEXT NOT NULL
        )""")
        db.execSQL("""CREATE TABLE signals (
            id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL,
            symbol TEXT NOT NULL, direction TEXT NOT NULL, flash_confidence INTEGER NOT NULL,
            pro_confidence INTEGER NOT NULL, decision TEXT NOT NULL, reason TEXT NOT NULL
        )""")
        db.execSQL("CREATE INDEX idx_trades_result_due ON trades(result, due_at)")
        db.execSQL("CREATE INDEX idx_signals_created ON signals(created_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun addSignal(symbol: String, direction: String, flash: Int, pro: Int, decision: String, reason: String) {
        val v = ContentValues().apply {
            put("created_at", System.currentTimeMillis()); put("symbol", symbol); put("direction", direction)
            put("flash_confidence", flash); put("pro_confidence", pro); put("decision", decision); put("reason", reason)
        }
        writableDatabase.insert("signals", null, v)
    }

    @Synchronized fun hasOpenTrade(symbol: String): Boolean {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM trades WHERE symbol=? AND result='OPEN'", arrayOf(symbol)).use {
            return it.moveToFirst() && it.getInt(0) > 0
        }
    }

    @Synchronized fun openTrade(symbol: String, direction: String, price: Double, stake: Double, dueAt: Long, flash: Int, pro: Int, reason: String): Long {
        val v = ContentValues().apply {
            put("symbol", symbol); put("direction", direction); put("entry_price", price); put("stake", stake)
            put("opened_at", System.currentTimeMillis()); put("due_at", dueAt); put("result", "OPEN")
            put("flash_confidence", flash); put("pro_confidence", pro); put("reason", reason)
        }
        return writableDatabase.insert("trades", null, v)
    }

    @Synchronized fun dueOpenTrades(now: Long): List<PaperTrade> = queryTrades("result='OPEN' AND due_at<=?", arrayOf(now.toString()), 100)

    @Synchronized fun settleTrade(id: Long, exitPrice: Double, pnl: Double, result: String) {
        val v = ContentValues().apply { put("exit_price", exitPrice); put("pnl", pnl); put("result", result) }
        writableDatabase.update("trades", v, "id=? AND result='OPEN'", arrayOf(id.toString()))
    }

    @Synchronized fun openTrades(): List<PaperTrade> = queryTrades("result='OPEN'", emptyArray(), 20)
    @Synchronized fun recentTrades(limit: Int = 20): List<PaperTrade> = queryTrades("1=1", emptyArray(), limit)

    private fun queryTrades(where: String, args: Array<String>, limit: Int): List<PaperTrade> {
        val out = mutableListOf<PaperTrade>()
        readableDatabase.rawQuery("SELECT id,symbol,direction,entry_price,stake,opened_at,due_at,exit_price,pnl,result,flash_confidence,pro_confidence,reason FROM trades WHERE $where ORDER BY id DESC LIMIT $limit", args).use { c ->
            while (c.moveToNext()) {
                out += PaperTrade(
                    c.getLong(0), c.getString(1), c.getString(2), c.getDouble(3), c.getDouble(4),
                    c.getLong(5), c.getLong(6), if (c.isNull(7)) null else c.getDouble(7),
                    if (c.isNull(8)) null else c.getDouble(8), c.getString(9), c.getInt(10), c.getInt(11), c.getString(12)
                )
            }
        }
        return out
    }

    @Synchronized fun stats(): TradeStats {
        var total = 0; var wins = 0; var losses = 0; var pushes = 0; var pnl = 0.0
        readableDatabase.rawQuery("SELECT result, COUNT(*), COALESCE(SUM(pnl),0) FROM trades WHERE result!='OPEN' GROUP BY result", null).use { c ->
            while (c.moveToNext()) {
                val result = c.getString(0); val count = c.getInt(1); total += count; pnl += c.getDouble(2)
                when (result) { "WIN" -> wins += count; "LOSS" -> losses += count; "PUSH" -> pushes += count }
            }
        }
        val decisive = wins + losses
        return TradeStats(total, wins, losses, pushes, if (decisive == 0) 0.0 else wins * 100.0 / decisive, pnl)
    }

    @Synchronized fun recentSignals(limit: Int = 10): List<String> {
        val rows = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT created_at,symbol,direction,flash_confidence,pro_confidence,decision,reason FROM signals ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) rows += "${c.getLong(0)}|${c.getString(1)}|${c.getString(2)}|${c.getInt(3)}|${c.getInt(4)}|${c.getString(5)}|${c.getString(6)}"
        }
        return rows
    }
}
