// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnkiquestBrowserSessionBridgeTest {
    @Test
    fun `an old bootstrap cannot replace the new owners cookie`() =
        runBlocking {
            val bridge = AnkiquestBrowserSessionBridge()
            var account = "alice"
            val events = mutableListOf<String>()
            val oldResponse = CompletableDeferred<String>()
            val old =
                async(start = CoroutineStart.UNDISPATCHED) {
                    bridge.prepare(
                        current = { account == "alice" },
                        clear = {
                            events += "clear alice"
                            true
                        },
                        bootstrap = { oldResponse.await() },
                        install = {
                            events += "install $it"
                            true
                        },
                    )
                }
            account = "bob"
            val next =
                async(start = CoroutineStart.UNDISPATCHED) {
                    bridge.prepare(
                        current = { account == "bob" },
                        clear = {
                            events += "clear bob"
                            true
                        },
                        bootstrap = { "bob" },
                        install = {
                            events += "install $it"
                            true
                        },
                    )
                }
            assertFalse(next.isCompleted)
            oldResponse.complete("alice")
            assertFalse(old.await())
            assertTrue(next.await())
            assertEquals(listOf("clear alice", "clear bob", "install bob"), events)
            assertFalse(
                bridge.prepare({ account == "alice" }, {
                    events += "stale clear"
                    true
                }, { "alice" }, { true }),
            )
            assertEquals("install bob", events.last())
        }

    @Test
    fun `cancelling a screen waits for its pending cookie mutation before the next bootstrap`() =
        runBlocking {
            val bridge = AnkiquestBrowserSessionBridge()
            var account = "alice"
            val acknowledged = CompletableDeferred<Boolean>()
            val events = mutableListOf<String>()
            val old =
                async(start = CoroutineStart.UNDISPATCHED) {
                    bridge.prepare(
                        current = { account == "alice" },
                        clear = { true },
                        bootstrap = { "alice" },
                        install = {
                            events += "install alice started"
                            acknowledged.await()
                        },
                    )
                }
            old.cancel()
            account = "bob"
            val next =
                async(start = CoroutineStart.UNDISPATCHED) {
                    bridge.prepare(
                        current = { account == "bob" },
                        clear = {
                            events += "clear bob"
                            true
                        },
                        bootstrap = { "bob" },
                        install = {
                            events += "install bob"
                            true
                        },
                    )
                }
            yield()
            assertFalse(next.isCompleted)
            assertEquals(listOf("install alice started"), events)
            acknowledged.complete(true)
            old.join()
            assertTrue(next.await())
            assertEquals(listOf("install alice started", "clear bob", "install bob"), events)
        }

    @Test
    fun `cookie rejection never authorizes loading but an older public server can load after a clear`() =
        runBlocking {
            val bridge = AnkiquestBrowserSessionBridge()
            var bootstraps = 0
            assertFalse(
                bridge.prepare({ true }, { false }, {
                    bootstraps++
                    "cookie"
                }, { true }),
            )
            assertEquals(0, bootstraps)
            assertFalse(bridge.prepare({ true }, { true }, { "cookie" }, { false }))
            assertTrue(bridge.prepare({ true }, { true }, { null }, { error("No cookie to install") }))
        }
}
