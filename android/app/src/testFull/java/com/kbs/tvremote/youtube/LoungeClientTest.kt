package com.kbs.tvremote.youtube

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LoungeClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: LoungeClient
    private val workers = Executors.newCachedThreadPool()
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("lounge-test", Context.MODE_PRIVATE)

    @Before fun setup() {
        prefs.edit().clear().commit()
        server = MockWebServer().apply { start() }
        client = LoungeClient(OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build(), prefs,
            server.url("/").toString().trimEnd('/'))
    }

    @After fun teardown() {
        client.stopWatching()
        workers.shutdownNow()
        workers.awaitTermination(3, TimeUnit.SECONDS)
        server.shutdown()
    }

    private fun pairing() = MockResponse().setBody("""{"screen":{"screenId":"screen","loungeToken":"token"}}""")
    private fun session() = MockResponse().setBody("""[[0,["c","session"]],[1,["S","gsession"]]]""")

    @Test fun malformedPairResponseDoesNotLeaveHalfLinkedTv() {
        server.enqueue(MockResponse().setBody("""{"screen":{"screenId":"screen"}}"""))
        assertThrows(IOException::class.java) { client.pair("123456789012") }
        assertFalse(client.isLinked)
    }

    @Test fun unlinkDuringPairingCannotRestoreOldLink() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                entered.countDown()
                finish.await(2, TimeUnit.SECONDS)
                return pairing()
            }
        }
        val result = workers.submit<Boolean> {
            try { client.pair("123456789012"); true } catch (_: IOException) { false }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            client.unlink()
        } finally { finish.countDown() }
        assertFalse(result.get(3, TimeUnit.SECONDS))
        assertFalse(client.isLinked)
    }

    @Test fun incompleteSessionIsNotReusedOnNextCommand() {
        server.enqueue(pairing())
        client.pair("123456789012")
        server.enqueue(MockResponse().setBody("""[[0,["c","partial"]]]"""))
        assertThrows(IOException::class.java) { client.play() }
        server.enqueue(session())
        server.enqueue(MockResponse().setBody("[]"))
        client.play()
        val requests = (1..4).map { server.takeRequest(1, TimeUnit.SECONDS)!! }
        assertEquals("1", requests[2].requestUrl!!.queryParameter("RID"))
        assertEquals("session", requests[3].requestUrl!!.queryParameter("SID"))
    }

    @Test fun failedPollBacksOffAndStopInterruptsBackoff() {
        val polls = AtomicInteger()
        val pollSeen = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.contains("get_screen")) return pairing()
                if (request.requestUrl!!.queryParameter("RID") == "rpc") {
                    polls.incrementAndGet()
                    pollSeen.countDown()
                    return MockResponse().setResponseCode(404)
                }
                return if (request.requestUrl!!.queryParameter("RID") == "1") session()
                    else MockResponse().setBody("[]")
            }
        }
        client.pair("123456789012")
        val watching = workers.submit { client.watch {} }
        assertTrue(pollSeen.await(3, TimeUnit.SECONDS))
        Thread.sleep(150)
        assertEquals(1, polls.get())
        client.stopWatching()
        watching.get(1, TimeUnit.SECONDS)
    }

    @Test fun invalidTokenRefreshIsReportedAsIoError() {
        prefs.edit().putString("yt_screen_id", "saved").commit()
        server.enqueue(MockResponse().setBody("""{"screens":[]}"""))
        assertThrows(IOException::class.java) { client.play() }
    }

    @Test fun invalidSeekDoesNotSendACommand() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0).forEach { position ->
            assertThrows(IOException::class.java) { client.seekTo(position) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun stopCancelsInitialCommandInsteadOfWaitingForItsTimeout() {
        val commandSeen = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.contains("get_screen")) return pairing()
                if (request.requestUrl!!.queryParameter("RID") == "1") return session()
                commandSeen.countDown()
                return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }
        }
        client.pair("123456789012")
        val watching = workers.submit { client.watch {} }
        assertTrue(commandSeen.await(3, TimeUnit.SECONDS))
        client.stopWatching()
        watching.get(1, TimeUnit.SECONDS)
    }

    @Test fun startingNewWatcherStopsOldWatcherWithoutStoppingTheNewOne() {
        val firstPoll = CountDownLatch(1)
        val secondPoll = CountDownLatch(2)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.contains("get_screen")) return pairing()
                val rid = request.requestUrl!!.queryParameter("RID")
                if (rid == "1") return session()
                if (rid == "rpc") {
                    firstPoll.countDown()
                    secondPoll.countDown()
                    return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
                return MockResponse().setBody("[]")
            }
        }
        client.pair("123456789012")
        val old = workers.submit { client.watch {} }
        assertTrue(firstPoll.await(3, TimeUnit.SECONDS))
        val replacement = workers.submit { client.watch {} }
        assertTrue(secondPoll.await(3, TimeUnit.SECONDS))
        old.get(1, TimeUnit.SECONDS)
        assertFalse(replacement.isDone)
        client.stopWatching()
        replacement.get(1, TimeUnit.SECONDS)
    }

    @Test fun partialStateUpdateKeepsTimingAndEmptyVideoClearsOldPlayback() {
        val json = """[[2,["nowPlaying",{"videoId":"video","state":"1","currentTime":"7","duration":"100"}]],[3,["onStateChange",{"state":"2"}]],[4,["nowPlaying",{"videoId":""}]],[5,["onStateChange",{"state":"1"}]]]"""
        server.enqueue(pairing())
        client.pair("123456789012")
        server.enqueue(session())
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("${json.length}\n$json"))
        val events = CopyOnWriteArrayList<Playback?>()
        val seen = CountDownLatch(3)
        val watching = workers.submit { client.watch { events += it; seen.countDown() } }
        assertTrue(seen.await(3, TimeUnit.SECONDS))
        client.stopWatching()
        watching.get(1, TimeUnit.SECONDS)
        assertEquals(3, events.size)
        assertEquals(PlaybackState.PAUSED, events[1]!!.state)
        assertEquals(7.0, events[1]!!.position, 0.0)
        assertEquals(100.0, events[1]!!.duration, 0.0)
        assertNull(events[2])
    }
}
