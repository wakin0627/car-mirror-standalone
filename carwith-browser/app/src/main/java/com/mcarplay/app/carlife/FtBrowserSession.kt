package com.mcarplay.app.carlife

import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Experimental six-channel, plaintext CarLife receiver for a locally running FT session. */
class FtBrowserSession(
    private val width: Int,
    private val height: Int,
    private val onVideo: (CarLifeVideoAccessUnit) -> Unit,
    private val onAudio: (CarLifeChannel, CarLifeAudioEvent) -> Unit,
    private val onEnded: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val channels = listOf(CarLifeChannel.COMMAND, CarLifeChannel.VIDEO, CarLifeChannel.MEDIA, CarLifeChannel.TTS, CarLifeChannel.VOICE, CarLifeChannel.TOUCH)
    private val sockets = java.util.concurrent.ConcurrentHashMap<CarLifeChannel, Socket>()
    private val video = CarLifeVideoParser()
    private val audio = listOf(CarLifeChannel.MEDIA, CarLifeChannel.TTS, CarLifeChannel.VOICE).associateWith { CarLifeAudioParser() }
    private val touch = CarLifeTouchState()
    @Volatile var videoWidth = width
        private set
    @Volatile var videoHeight = height
        private set
    @Volatile private var active = false

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "ft-connection", isDaemon = true) {
            try {
                // FT opens six CarLife listeners, no Xiaomi-specific DATA channel.
                while (running.get()) {
                    try {
                        channels.forEach { sockets[it] = connectCarLifeSocket("127.0.0.1", it.port, 1000) }
                        break
                    } catch (e: Exception) {
                        sockets.values.forEach { runCatching { it.close() } }
                        sockets.clear()
                        if (running.get()) Thread.sleep(1000)
                    }
                }
                if (!running.get()) return@thread
                listOf(CarLifeChannel.VIDEO, CarLifeChannel.MEDIA, CarLifeChannel.TTS, CarLifeChannel.VOICE).forEach { ch ->
                    thread(name = "ft-read-${ch.name}", isDaemon = true) {
                        try {
                            val input = sockets.getValue(ch).getInputStream()
                            while (running.get()) {
                                val frame = readCarLifeSocket(input, ch.port)
                                if (ch == CarLifeChannel.VIDEO && frame.kind == CarLifeProtocol.VIDEO_FRAME) {
                                    video.push(frame.timestamp.toLong() and 0xffffffffL, frame.payload).forEach { unit ->
                                        unit.dimensions()?.let { (w, h) -> videoWidth = w; videoHeight = h }
                                        onVideo(unit)
                                    }
                                } else if (ch != CarLifeChannel.VIDEO) {
                                    audio[ch]?.parse(ch, frame)?.let { onAudio(ch, it) }
                                }
                            }
                        } catch (e: Exception) { end("${ch.name}：${e.message}") }
                    }
                }
                command(CarLifeProtocol.HU_PROTOCOL_VERSION, CarLifeProtocol.protocolVersionPayload())
                val input = sockets.getValue(CarLifeChannel.COMMAND).getInputStream()
                while (running.get()) {
                    val frame = readCarLifeSocket(input, CarLifeChannel.COMMAND.port)
                    when (frame.kind) {
                        CarLifeProtocol.MD_PROTOCOL_MATCH -> {
                            if (CarLifeProtocol.firstIntField(frame.payload, 1) != 1) throw IllegalStateException("FT 协议不匹配")
                            command(CarLifeProtocol.HU_INFO, CarLifeProtocol.huInfoPayload())
                            command(CarLifeProtocol.HU_GO_TO_FOREGROUND)
                            command(CarLifeProtocol.HU_STATISTICS, CarLifeProtocol.statisticsPayload())
                            command(CarLifeProtocol.VIDEO_ENCODER_INIT, CarLifeProtocol.videoInitPayload(width, height, 30))
                        }
                        CarLifeProtocol.MD_FEATURE_REQUEST -> command(CarLifeProtocol.HU_FEATURE_RESPONSE, CarLifeProtocol.featureResponsePayload(mapOf("CONTENT_ENCRYPTION" to 0)))
                        CarLifeProtocol.VIDEO_ENCODER_INIT_DONE -> {
                            CarLifeProtocol.parseVideoFormat(frame.payload)?.let { videoWidth = it.width; videoHeight = it.height }
                            command(CarLifeProtocol.VIDEO_ENCODER_START)
                            active = true
                        }
                    }
                }
            } catch (e: Exception) { end(e.message ?: "连接已结束") }
        }
    }

    fun requestKeyFrame() { if (active) command(CarLifeProtocol.VIDEO_ENCODER_RESET) }
    fun sendTouch(x: Int, y: Int, down: Boolean) {
        if (!active) return
        val action = touch.onContact(x, y, down, videoWidth, videoHeight) ?: return
        val kind = when (action) {
            CarLifeTouchAction.DOWN -> CarLifeProtocol.TOUCH_DOWN
            CarLifeTouchAction.MOVE -> CarLifeProtocol.TOUCH_MOVE
            CarLifeTouchAction.UP -> CarLifeProtocol.TOUCH_UP
        }
        val socket = sockets[CarLifeChannel.TOUCH] ?: return
        try { synchronized(socket) { socket.getOutputStream().write(CarLifeProtocol.encodeCommand(kind, CarLifeProtocol.touchPayload(x, y))) } }
        catch (e: Exception) { end(e.message ?: "触控连接中断") }
    }
    private fun command(kind: Int, payload: ByteArray = ByteArray(0)) {
        val socket = sockets[CarLifeChannel.COMMAND] ?: return
        synchronized(socket) { socket.getOutputStream().write(CarLifeProtocol.encodeCommand(kind, payload)) }
    }
    private fun end(reason: String) {
        if (!running.compareAndSet(true, false)) return
        active = false
        sockets.values.forEach { runCatching { it.close() } }
        sockets.clear()
        onEnded(reason)
    }
    fun stop() { if (running.getAndSet(false)) { active = false; sockets.values.forEach { runCatching { it.close() } }; sockets.clear() } }
}
