package com.mcarplay.app.carlife

import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FtBrowserSessionTest {
    @Test fun connectsSixFtChannelsAndRoutesVideoSetupAndTouch() {
        val channels = listOf(CarLifeChannel.COMMAND, CarLifeChannel.VIDEO, CarLifeChannel.MEDIA, CarLifeChannel.TTS, CarLifeChannel.VOICE, CarLifeChannel.TOUCH)
        val listeners = channels.associateWith { ServerSocket(it.port, 1).apply { soTimeout = 5000 } }
        val connected = HashMap<CarLifeChannel, java.net.Socket>()
        val videoSeen = CountDownLatch(1)
        val audioSeen = CountDownLatch(1)
        val client = FtBrowserSession(1280, 800, { videoSeen.countDown() }, { _, event -> if (event is CarLifeAudioEvent.Pcm) audioSeen.countDown() }, {})
        try {
            client.start()
            channels.forEach { ch -> connected[ch] = listeners.getValue(ch).accept().apply { soTimeout = 5000 } }
            val command = connected.getValue(CarLifeChannel.COMMAND)
            val input = command.getInputStream()
            assertEquals(CarLifeProtocol.HU_PROTOCOL_VERSION, readCarLifeSocket(input, 7240).kind)
            command.getOutputStream().write(CarLifeProtocol.encodeCommand(CarLifeProtocol.MD_PROTOCOL_MATCH, CarLifeProtocol.pbInt(1, 1)))
            val kinds = (1..4).map { readCarLifeSocket(input, 7240).kind }
            assertTrue(kinds.contains(CarLifeProtocol.HU_INFO))
            assertTrue(kinds.contains(CarLifeProtocol.HU_STATISTICS))
            assertTrue(kinds.contains(CarLifeProtocol.VIDEO_ENCODER_INIT))
            command.getOutputStream().write(CarLifeProtocol.encodeCommand(CarLifeProtocol.MD_FEATURE_REQUEST, byteArrayOf()))
            val features = readCarLifeSocket(input, 7240)
            assertEquals(0, CarLifeProtocol.parseFeatures(features.payload)["CONTENT_ENCRYPTION"])
            command.getOutputStream().write(CarLifeProtocol.encodeCommand(CarLifeProtocol.VIDEO_ENCODER_INIT_DONE, CarLifeProtocol.videoInitPayload(1280, 800, 30)))
            assertEquals(CarLifeProtocol.VIDEO_ENCODER_START, readCarLifeSocket(input, 7240).kind)
            val mediaOut = connected.getValue(CarLifeChannel.MEDIA).getOutputStream()
            val audioFormat = CarLifeProtocol.pbInt(1, 48000) + CarLifeProtocol.pbInt(2, 2) + CarLifeProtocol.pbInt(3, 16)
            mediaOut.write(CarLifeProtocol.encodeVideo(CarLifeProtocol.MEDIA_INIT, 1, audioFormat))
            mediaOut.write(CarLifeProtocol.encodeVideo(CarLifeProtocol.MEDIA_DATA, 1, byteArrayOf(0, 0, 0, 0)))
            assertTrue("PCM did not reach bridge", audioSeen.await(3, TimeUnit.SECONDS))
            val videoOut = connected.getValue(CarLifeChannel.VIDEO).getOutputStream()
            videoOut.write(CarLifeProtocol.encodeVideo(CarLifeProtocol.VIDEO_FRAME, 1, byteArrayOf(0, 0, 0, 1, 0x65, 0x80.toByte(), 1, 2)))
            videoOut.write(CarLifeProtocol.encodeVideo(CarLifeProtocol.VIDEO_FRAME, 2, byteArrayOf(0, 0, 0, 1, 0x41, 0x80.toByte(), 3, 4)))
            assertTrue("H264 did not reach bridge", videoSeen.await(3, TimeUnit.SECONDS))
            // Let the command reader mark the session active after it sends START.
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var received: CarLifeFrame? = null
            connected.getValue(CarLifeChannel.TOUCH).soTimeout = 100
            while (received == null && System.nanoTime() < end) {
                client.sendTouch(120, 90, true)
                try { received = readCarLifeSocket(connected.getValue(CarLifeChannel.TOUCH).getInputStream(), 9340) }
                catch (_: java.net.SocketTimeoutException) { Thread.sleep(20) }
            }
            assertEquals(CarLifeProtocol.TOUCH_DOWN, received?.kind)
        } finally {
            client.stop()
            connected.values.forEach { it.close() }
            listeners.values.forEach { it.close() }
        }
    }
}
