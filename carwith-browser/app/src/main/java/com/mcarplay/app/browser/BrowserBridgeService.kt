package com.mcarplay.app.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.mcarplay.app.audio.AudioDuckingMixer
import com.mcarplay.app.audio.PcmResampler
import com.mcarplay.app.carlife.CarLifeAudioEvent
import com.mcarplay.app.carlife.CarLifeChannel
import com.mcarplay.app.carlife.CarLifeSession
import com.mcarplay.app.carlife.CarLifeVideoAccessUnit
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/** Receives Xiaomi CarWith's CarLife stream locally and relays it to a car browser. */
class BrowserBridgeService :
    Service(),
    MirrorWebServer.Listener {
    companion object {
        const val ACTION_START = "com.mcarplay.app.browser.START"
        const val ACTION_STOP = "com.mcarplay.app.browser.STOP"

        val token: String = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val pin: String = "%06d".format(SecureRandom().nextInt(1_000_000))

        @JvmField
        @Volatile
        var state = "未连接"

        @JvmField
        @Volatile
        var videoReady = false

        @JvmField
        @Volatile
        var browserConnected = false
    }

    @Volatile
    private var web: MirrorWebServer? = null

    @Volatile
    private var session: CarLifeSession? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var stopping = false

    @Volatile
    private var starting = false
    private var videoFrames = 0L
    private var lastCodecConfig: ByteArray? = null
    private val audioLock = Any()
    private val resamplers = mutableMapOf<CarLifeChannel, PcmResampler>()
    private val mixer = AudioDuckingMixer<CarLifeChannel>(48_000)
    private var audioFlusher: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (starting || web != null) return START_STICKY
        starting = true
        showNotification()
        state = "正在启动 CarWith 网页桥接"
        val width = intent?.getIntExtra("width", 1280)?.takeIf { it in 640..2560 } ?: 1280
        val ratio = intent?.getIntExtra("ratio", 0) ?: 0
        val height =
            when (ratio) {
                1 -> width * 9 / 16
                2 -> width / 2
                else -> width * 5 / 8
            }
        Thread({ startBridge(width, height) }, "carwith-browser-start").start()
        return START_STICKY
    }

    private fun startBridge(
        width: Int,
        height: Int,
    ) {
        try {
            val server = MirrorWebServer(this, this, token, pin)
            server.start()
            if (stopping) {
                server.stop()
                return
            }
            web = server
            if (stopping) return
            val power = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CarWithBrowser:bridge").also { it.acquire() }
            val client =
                CarLifeSession(
                    application = application,
                    onAccessUnit = ::relayVideo,
                    onAudioEvent = ::relayAudio,
                    onEnded = { reason ->
                        state = "CarWith 会话结束：$reason"
                        stopSelf()
                    },
                    log = { message -> Log.i("CarWithBrowser", message) },
                    requestedVideoSize = width to height,
                )
            session = client
            if (stopping) return
            if (!client.start()) throw IllegalStateException("无法启动 CarWith 会话")
            audioFlusher =
                Thread({
                    while (!stopping) {
                        try {
                            Thread.sleep(100)
                        } catch (_: InterruptedException) {
                            break
                        }
                        val pcm = synchronized(audioLock) { mixer.drain() }
                        if (pcm.isNotEmpty()) web?.broadcastAudio(pcm)
                    }
                }, "carwith-browser-audio-flush").also { it.start() }
            if (!stopping) state = "等待 CarWith 画面；车机可打开网页"
        } catch (error: Exception) {
            Log.e("CarWithBrowser", "Startup failed", error)
            state = "启动失败：${error.message}"
            stopSelf()
        } finally {
            starting = false
        }
    }

    private fun relayVideo(unit: CarLifeVideoAccessUnit) {
        if (stopping) return
        if (unit.sps != null && unit.pps != null) {
            val config = startCode() + unit.sps + startCode() + unit.pps
            if (lastCodecConfig?.contentEquals(config) != true) {
                lastCodecConfig = config
                web?.broadcastVideo(packet(config, key = false, config = true), true, false)
            }
        }
        if (!hasVideoSlice(unit.data)) return
        web?.broadcastVideo(packet(unit.data, key = unit.keyFrame, config = false), false, unit.keyFrame)
        videoFrames++
        if (videoFrames == 1L) {
            videoReady = true
            state = if (browserConnected) "车机已连接" else "CarWith 画面已接收，等待车机浏览器"
        }
    }

    private fun relayAudio(
        channel: CarLifeChannel,
        event: CarLifeAudioEvent,
    ) {
        if (stopping) return
        val output =
            synchronized(audioLock) {
                when (event) {
                    is CarLifeAudioEvent.Init -> {
                        resamplers.remove(channel)
                        byteArrayOf()
                    }
                    is CarLifeAudioEvent.Stop -> {
                        resamplers.remove(channel)
                        if (channel == CarLifeChannel.MEDIA) mixer.mediaStopped()
                        mixer.drain()
                    }
                    is CarLifeAudioEvent.Pcm -> {
                        val converter =
                            resamplers[channel]?.takeIf {
                                it.sourceRate == event.sampleRate && it.sourceChannels == event.channels
                            } ?: PcmResampler(event.sampleRate, event.channels, 48_000).also { resamplers[channel] = it }
                        val pcm = converter.convert(event.littleEndian)
                        if (channel == CarLifeChannel.MEDIA) {
                            mixer.media(pcm)
                        } else {
                            mixer.prompt(channel, pcm)
                            mixer.drain()
                        }
                    }
                }
            }
        if (output.isNotEmpty()) web?.broadcastAudio(output)
    }

    override fun onTouch(data: ByteArray) {
        if (data.size < 22 || data[0].toInt() != 2) return
        val input = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val action = data[1].toInt()
        val pointer = input.getLong(2)
        if (pointer != 0L || action !in 0..2) return
        val width = input.getShort(18).toInt() and 0xffff
        val height = input.getShort(20).toInt() and 0xffff
        val client = session ?: return
        if (width == 0 || height == 0) return
        val x = (input.getInt(10).toLong() * client.videoWidth / width).toInt().coerceIn(0, client.videoWidth - 1)
        val y = (input.getInt(14).toLong() * client.videoHeight / height).toInt().coerceIn(0, client.videoHeight - 1)
        client.sendTouch(x, y, action != 1)
    }

    override fun onCommand(action: String?): String? {
        if (action == "disconnect") stopSelf()
        if (action == "getDisplayState") return "{\"display\":\"on\"}"
        return null
    }

    private fun showNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("carwith_browser", "CarWith 网页桥接", NotificationManager.IMPORTANCE_LOW))
        val stop = Intent(this, BrowserBridgeService::class.java).setAction(ACTION_STOP)
        val pending = PendingIntent.getService(this, 48, stop, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification =
            Notification
                .Builder(this, "carwith_browser")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("CarWith 网页桥接运行中")
                .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "断开", pending).build())
                .build()
        startForeground(448, notification)
    }

    override fun onDestroy() {
        stopping = true
        audioFlusher?.interrupt()
        audioFlusher = null
        videoReady = false
        browserConnected = false
        session?.stop()
        session = null
        web?.stop()
        web = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        synchronized(audioLock) {
            resamplers.clear()
            mixer.reset()
        }
        if (!state.startsWith("启动失败") && !state.startsWith("CarWith 会话结束")) state = "已断开"
        super.onDestroy()
    }

    private fun packet(
        data: ByteArray,
        key: Boolean,
        config: Boolean,
    ): ByteArray = byteArrayOf(if (key) 1 else 0, if (config) 1 else 0) + data

    private fun startCode(): ByteArray = byteArrayOf(0, 0, 0, 1)

    private fun hasVideoSlice(data: ByteArray): Boolean {
        for (i in 0 until data.size - 4) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                val type = data[i + 4].toInt() and 0x1f
                if (type == 1 || type == 5) return true
            }
        }
        return false
    }
}
