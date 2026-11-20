package com.mcarplay.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MirrorWebServerVideoTest {
    @Test
    fun congestionRequestsKeyframeAndRecoverySendsCurrentConfiguration() {
        val request = CountDownLatch(1)
        val listener =
            object : MirrorWebServer.Listener {
                override fun onTouch(data: ByteArray) = Unit

                override fun onCommand(action: String?): String? {
                    if (action == "requestKeyFrame") request.countDown()
                    return null
                }
            }
        val server = MirrorWebServer(null, listener, "token", "123456")
        val type = server.javaClass.declaredClasses.single { it.simpleName == "Client" }
        val constructor = type.declaredConstructors.single().apply { isAccessible = true }
        val client = constructor.newInstance(server, Socket(), ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), false)
        val clients = server.javaClass.getDeclaredField("clients").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (clients.get(server) as MutableSet<Any>).add(client)
        val queueField = type.getDeclaredField("queue").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val queue = queueField.get(client) as ArrayBlockingQueue<ByteArray>
        val oldConfig = byteArrayOf(0, 1, 11)
        val newConfig = byteArrayOf(0, 1, 22)
        server.broadcastVideo(oldConfig, true, false)
        server.broadcastVideo(byteArrayOf(1, 0, 1), false, true)
        server.broadcastVideo(newConfig, true, false)
        val cachedKey = server.javaClass.getDeclaredField("lastKeyframe").apply { isAccessible = true }
        assertNull("Old keyframe must not accompany a new config", cachedKey.get(server))
        repeat(8) { server.broadcastVideo(byteArrayOf(0, 0, 3), false, false) }
        assertTrue("Queue overflow must request a fresh keyframe", request.await(2, TimeUnit.SECONDS))
        server.broadcastVideo(byteArrayOf(1, 0, 4), false, true)
        assertEquals(2, queue.size)
        assertTrue(
            queue
                .take()
                .takeLast(newConfig.size)
                .toByteArray()
                .contentEquals(newConfig),
        )
        assertTrue(
            queue
                .take()
                .takeLast(3)
                .toByteArray()
                .contentEquals(byteArrayOf(1, 0, 4)),
        )
    }
}
