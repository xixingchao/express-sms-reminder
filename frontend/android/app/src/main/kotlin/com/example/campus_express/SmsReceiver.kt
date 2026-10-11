package com.example.campus_express

// 后台快递短信接收器（妈妈版核心）。
//
// Manifest 静态注册：App 进程被杀也能收到系统短信广播，命中快递关键词就弹通知。
// 通知文本直接带取件码和驿站，点开看详情无需解锁 App；附"复制取件码"动作。
// 只读 intent 内容、发通知，不写任何存储——完整解析入库仍由 Flutter 侧
// 打开应用时扫收件箱完成（express.db，按 sms_id 去重），两端互不依赖。
//
// 关键词集合与 lib/services/sms_service.dart 的 kExpressKeywords 保持一致。

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.SmsMessage
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TELEPHONY_SMS_RECEIVED) return
        // RECEIVE_SMS 未授予时系统根本不会投递本广播，这里再校验一次兜底
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (msgs.isEmpty()) return
        // 长短信分段：把同一 intent 内的分段拼回完整正文
        val body = msgs.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }.trim()
        if (body.isEmpty()) return
        val sender = msgs[0].originatingAddress ?: ""
        val date = msgs[0].timestampMillis

        if (kExpressKeywords.none { body.contains(it) }) return

        val parsed = parseBriefly(body)
        notifyExpress(context, body, date, sender, parsed)
    }

    private fun notifyExpress(
        context: Context,
        body: String,
        date: Long,
        sender: String,
        parsed: BriefParsed,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "快递短信", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "收到快递短信时立即提醒"
        }
        nm.createNotificationChannel(channel)

        val title = buildString {
            append(parsed.company.ifEmpty { "快递" })
            append("快递")
            if (parsed.station.isNotEmpty()) append(" · ").append(parsed.station)
        }
        val text = if (parsed.hasCode) "取件码 ${parsed.code}" else "收到快递短信，点开查看"

        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val copyAction = PendingIntent.getBroadcast(
            context, parsed.code.hashCode(),
            Intent(context, CopyCodeReceiver::class.java)
                .putExtra(CopyCodeReceiver.EXTRA_CODE, parsed.code),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val timeText = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(date))
        val bigText = if (parsed.hasCode) "$text\n$timeText\n$body" else "$timeText\n$body"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_MESSAGE)
        // 码没识别出来就不给复制按钮，避免复制到"未知取件码"
        if (parsed.hasCode) {
            builder.addAction(0, "复制取件码", copyAction)
        }

        // POST_NOTIFICATIONS（Android 13+）未授予时静默放弃，不崩
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED && android.os.Build.VERSION.SDK_INT >= 33
        ) return
        // 用 (sender+date) 做 id：同一条短信重复投递时覆盖而非重复弹
        nm.notify((sender + date).hashCode(), builder.build())
    }

    private data class BriefParsed(val company: String, val station: String, val code: String) {
        val hasCode get() = code.isNotEmpty() && code != "未知取件码"
    }

    // Dart 侧 SmsParser 的 Kotlin 简版，仅用于通知文本，不求完全一致。
    private fun parseBriefly(body: String): BriefParsed {
        val codePatterns = listOf(
            Regex("""取件码[：:\s]*([A-Za-z0-9\-]+)"""),
            Regex("""凭([A-Za-z0-9\-]+)取件"""),
            Regex("""([0-9]{1,2}-[0-9]{1,2}-[0-9]{4})"""),
            Regex("""【([A-Za-z0-9\-]+)】"""),
        )
        var code = ""
        for (p in codePatterns) {
            val m = p.find(body)
            if (m != null) {
                code = m.groupValues[1].trim()
                break
            }
        }

        val companyRe = Regex(
            "极兔速递|极兔|顺丰速运|顺丰|京东物流|京东|中国邮政|邮政EMS|EMS|中通|圆通|申通|韵达|德邦|丹鸟|天天|百世|宅急送|丰网|苏宁"
        )
        var company = companyRe.find(body)?.value ?: ""
        if (company.isEmpty()) {
            val sig = Regex("""【(.+?)】""").find(body)?.groupValues?.get(1)?.trim() ?: ""
            if (sig.isNotEmpty() && !sig.contains("菜鸟") && !sig.contains("驿站")) company = sig
        }

        val stationRe = Regex("""(?:请到|请前往|已送达|已到达|已抵|已到|送达|已放在|放在)(.+?)(?:取件|自提|[，,。])""")
        val station = stationRe.find(body)?.groupValues?.get(1)?.trim() ?: ""

        return BriefParsed(company, station, code)
    }

    companion object {
        private const val TELEPHONY_SMS_RECEIVED = "android.provider.Telephony.SMS_RECEIVED"
        private const val CHANNEL_ID = "express_sms"

        // 与 lib/services/sms_service.dart 的 kExpressKeywords 一致（宽抓，宁多勿漏）
        val kExpressKeywords = listOf(
            "取件", "凭", "快递", "驿站", "包裹", "丰巢", "菜鸟", "兔喜",
            "快递柜", "自提", "派送", "签收", "已放", "代收",
        )
    }
}

class CopyCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra(EXTRA_CODE) ?: return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("取件码", code))
        android.widget.Toast.makeText(context, "已复制取件码 $code", android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_CODE = "code"
    }
}
