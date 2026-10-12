package com.cofbro.qian.utils

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 签到报告收集器
 *
 * 所有签到流程的关键步骤都写到这里, 供"签到报告"(首页LOG / 个人中心→签到报告)查看。
 * 性能约定:
 * - 纯内存环形缓冲, 不落盘、不做任何IO
 * - 时间格式化用 ThreadLocal 复用, 不每次new
 * - 单条日志超长时截断, 防止HTML响应撑爆内存
 */
object DebugLogCollector {
    private val logList = mutableListOf<String>()
    private const val MAX_LOG_SIZE = 400          // 最多保留条数(约百KB级, 可忽略)
    private const val MAX_LINE_LENGTH = 600       // 单条最大长度

    private val timeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }
    private val reportTimeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        addLog("D", tag, msg)
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        Log.e(tag, msg, throwable)
        addLog("E", tag, msg)
        throwable?.message?.let { addLog("E", tag, it) }
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        addLog("W", tag, msg)
    }

    /** 报告里的分节标记, 让一次签到流程的起止一目了然 */
    fun section(title: String) {
        Log.d("SignReport", "━━━ $title ━━━")
        addLog("", "", "━━━━━━ $title ━━━━━━", raw = true)
    }

    private fun addLog(level: String, tag: String, msg: String, raw: Boolean = false) {
        val time = timeFormat.get()?.format(Date()) ?: ""
        val text = if (msg.length > MAX_LINE_LENGTH) msg.take(MAX_LINE_LENGTH) + "…(截断)" else msg
        val line = if (raw) "[$time] $text" else "[$time][$level][$tag] $text"
        synchronized(logList) {
            if (logList.size >= MAX_LOG_SIZE) {
                logList.removeAt(0)
            }
            logList.add(line)
        }
    }

    /** 组装完整报告(仅在打开/复制报告时调用) */
    fun getReport(): String {
        val logs = synchronized(logList) { logList.toList() }
        val header = buildString {
            append("📋 签到报告\n")
            append("生成时间: ").append(reportTimeFormat.get()?.format(Date()) ?: "").append("\n")
            append("日志条数: ").append(logs.size).append(" / 最多保留 ").append(MAX_LOG_SIZE).append("\n")
            append("────────────────────\n")
        }
        if (logs.isEmpty()) return header + "(暂无日志, 去签一次到试试)\n"
        return header + logs.joinToString("\n")
    }

    fun clear() {
        synchronized(logList) {
            logList.clear()
        }
    }
}
