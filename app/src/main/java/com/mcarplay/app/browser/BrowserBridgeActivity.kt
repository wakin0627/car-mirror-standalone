package com.mcarplay.app.browser

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

/** One-screen setup: CarWith renders independently; the browser receives its native stream. */
class BrowserBridgeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var address: TextView
    private lateinit var pin: TextView
    private lateinit var quality: Spinner
    private lateinit var ratio: Spinner
    private val refresh =
        object : Runnable {
            override fun run() {
                status.text = BrowserBridgeService.state
                address.text = currentUrl() ?: "请先连接车机 Wi-Fi"
                handler.postDelayed(this, 1000)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(30), dp(24), dp(24))
                setBackgroundColor(Color.rgb(247, 248, 250))
            }
        root.addView(label("CarWith 网页桥接", 27, Color.rgb(24, 32, 45)))
        root.addView(label("小米 13 连接车机 Wi-Fi 后，点连接；车机浏览器打开下方网址。", 16, Color.DKGRAY))
        status = label("未连接", 19, Color.rgb(38, 90, 160)).also(root::addView)
        address = label("", 18, Color.BLACK).also(root::addView)
        pin = label("配对码：${BrowserBridgeService.pin}", 20, Color.rgb(38, 90, 160)).also(root::addView)
        root.addView(label("画质", 16, Color.DKGRAY))
        quality = spinner("低延迟：宽 1280", "标准：宽 1920").also(root::addView)
        root.addView(label("车机比例", 16, Color.DKGRAY))
        ratio = spinner("16:10", "16:9", "2:1").also(root::addView)
        button(root, "连接，显示 CarWith 画面") {
            if (currentUrl() == null) {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                return@button
            }
            val service = Intent(this, BrowserBridgeService::class.java).setAction(BrowserBridgeService.ACTION_START)
            service.putExtra("width", if (quality.selectedItemPosition == 0) 1280 else 1920)
            service.putExtra("ratio", ratio.selectedItemPosition)
            startForegroundService(service)
            status.text = "正在启动"
        }
        button(root, "断开连接") {
            startService(Intent(this, BrowserBridgeService::class.java).setAction(BrowserBridgeService.ACTION_STOP))
        }
        button(root, "复制车机网址") {
            currentUrl()?.let { url ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("CarWith 网页桥接", url))
            }
        }
        root.addView(label("车机浏览器打开网址，输入上方六位配对码，再点“开启车机声音”。车机画面由 CarWith 生成，手机屏幕可独立使用。首次打开本地 HTTPS 证书可能需要在车机确认。", 14, Color.GRAY))
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun currentUrl(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                if (!network.name.startsWith("wlan") || !network.isUp) continue
                val addresses = network.inetAddresses
                while (addresses.hasMoreElements()) {
                    val ip = addresses.nextElement()
                    if (ip is Inet4Address && !ip.isLoopbackAddress) {
                        return "https://${ip.hostAddress}:8443/"
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun spinner(vararg items: String): Spinner =
        Spinner(this).apply {
            adapter = ArrayAdapter(this@BrowserBridgeActivity, android.R.layout.simple_spinner_dropdown_item, items)
        }

    private fun label(
        text: String,
        sp: Int,
        color: Int,
    ): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sp.toFloat()
            setTextColor(color)
            setPadding(0, 0, 0, dp(16))
        }

    private fun button(
        parent: LinearLayout,
        text: String,
        click: (View) -> Unit,
    ) {
        parent.addView(
            Button(this).apply {
                this.text = text
                isAllCaps = false
                setOnClickListener(click)
            },
            LinearLayout.LayoutParams(-1, dp(54)),
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
