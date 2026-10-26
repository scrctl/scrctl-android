package org.scrctl.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EmbeddedSdkTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun data() = File(context.filesDir,"test-${UUID.randomUUID()}").apply { mkdirs() }
    private fun waitUntil(test: () -> Boolean) {
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12)
        while(!test()) { if(System.nanoTime()>until) fail("Condition timed out"); Thread.sleep(50) }
    }
    @Test fun twoInProcessServersUseDistinctPortsAndExactSdk() {
        val a=data(); val b=data(); val r1=NativeBridge.start(a.absolutePath);val r2=NativeBridge.start(b.absolutePath)
        try {
            assertTrue(NativeBridge.port(r1)>0)
            assertNotEquals(NativeBridge.port(r1),NativeBridge.port(r2))
            for(r in listOf(r1,r2)) {
                val c=NativeBridge.connect(r,NativeEvents {})
                try {
                    val reply=JSONObject(NativeBridge.request(r,c,"server.info","{}").json)
                    assertTrue(reply.getBoolean("ok"))
                    assertEquals("1.0.1",reply.getJSONObject("result").getString("version"))
                    val invalid=JSONObject(NativeBridge.request(r,c,"devices","{\"timeout_ms\":-1}").json)
                    assertFalse(invalid.getBoolean("ok"))
                    assertTrue(JSONObject(NativeBridge.request(r,c,"server.info","{}").json).getBoolean("ok"))
                } finally { NativeBridge.close(r,c) }
            }
        } finally { NativeBridge.stop(r1);NativeBridge.stop(r2);a.deleteRecursively();b.deleteRecursively() }
    }
    @Test fun interruptionCanRaceRequestAndCloseWithoutDanglingHandles() {
        val dir=data();val r=NativeBridge.start(dir.absolutePath);val c=NativeBridge.connect(r,NativeEvents {})
        val worker=Executors.newSingleThreadExecutor()
        try {
            val task=worker.submit { runCatching { NativeBridge.request(r,c,"devices","{\"transport\":\"wifi\",\"timeout_ms\":5000}") } }
            Thread.sleep(100)
            NativeBridge.interrupt(r,c)
            NativeBridge.close(r,c)
            task.get(8,TimeUnit.SECONDS)
            NativeBridge.stop(r)
            assertTrue(runCatching { NativeBridge.port(r) }.isFailure)
        } finally { NativeBridge.stop(r);worker.shutdownNow();dir.deleteRecursively() }
    }
    @Test fun foregroundNetworkAndBackgroundTransitionsReleaseListeningSocket() {
        val root=data();val cache=File(context.cacheDir,"test-${UUID.randomUUID()}").apply{mkdirs()}
        val controller=SessionController(root,cache,{}, { it.bitmap.recycle() })
        try {
            repeat(3) {
                controller.resume();waitUntil { controller.serverPort()>0 }
                val port=controller.serverPort()
                controller.pause()
                waitUntil { controller.serverPort()==0 }
                waitUntil { runCatching { Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1",port),100) } }.isFailure }
            }
            controller.resume();waitUntil { controller.serverPort()>0 }
            controller.networkChanged(false);waitUntil { controller.serverPort()==0 }
            controller.networkChanged(true);waitUntil { controller.serverPort()>0 }
        } finally { controller.close();waitUntil { controller.serverPort()==0 }; root.deleteRecursively();cache.deleteRecursively() }
    }
}
