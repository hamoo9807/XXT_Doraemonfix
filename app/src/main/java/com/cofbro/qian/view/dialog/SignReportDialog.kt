package com.cofbro.qian.view.dialog

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.ScrollView
import android.widget.TextView
import com.cofbro.qian.utils.DebugLogCollector
import com.hjq.toast.ToastUtils

/**
 * 签到报告弹窗
 * 展示 [DebugLogCollector] 收集的签到全过程日志。
 * 打开/刷新时才拼接文本, 不参与签到链路, 无额外性能开销。
 */
class SignReportDialog(private val context: Context) {
    private var dialog: AlertDialog? = null
    private var textView: TextView? = null
    private var scrollView: ScrollView? = null

    fun show() {
        val tv = TextView(context).apply {
            textSize = 11f
            setPadding(28, 20, 28, 20)
            setTextIsSelectable(true)
            setLineSpacing(6f, 1.0f)
        }
        textView = tv
        val scroll = ScrollView(context).apply { addView(tv) }
        scrollView = scroll

        dialog = AlertDialog.Builder(context)
            .setTitle("📋 签到报告")
            .setView(scroll)
            .setPositiveButton("复制", null)
            .setNeutralButton("刷新", null)
            .setNegativeButton("关闭", null)
            .create()

        dialog?.setOnShowListener {
            dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("签到报告", DebugLogCollector.getReport()))
                ToastUtils.show("签到报告已复制")
            }
            dialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                refresh()
            }
        }
        dialog?.show()
        refresh()
    }

    private fun refresh() {
        textView?.text = DebugLogCollector.getReport()
        scrollView?.post { scrollView?.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
