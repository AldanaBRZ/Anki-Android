// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [32])
class AnkiquestDeckCopiesTest : RobolectricTest() {
    @Test
    fun `selected member can list download and dismiss a shared deck copy`() =
        runBlocking {
            val packageBytes = byteArrayOf(80, 75, 3, 4) + "sample".toByteArray()
            val requests = mutableListOf<String>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/api/deck-copies/hill") { exchange ->
                requests += "${exchange.requestMethod} ${exchange.requestHeaders.getFirst("Authorization")} ${exchange.requestURI.path}"
                val bytes =
                    when {
                        exchange.requestMethod == "DELETE" -> ByteArray(0)
                        exchange.requestURI.path.endsWith("/7") -> packageBytes
                        else ->
                            """{"offers":[{"id":7,"sender":"cerro","deck":"Spanish","bytes":10}],"friends":[],"max_bytes":26214400}"""
                                .toByteArray()
                    }
                exchange.sendResponseHeaders(if (exchange.requestMethod == "DELETE") 204 else 200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            try {
                val account = HomeAccount("http://127.0.0.1:${server.address.port}", "hill", "hill-secret")
                val inbox = AnkiquestDeckCopies.inbox(account)
                assertEquals("Spanish", inbox.getJSONArray("offers").getJSONObject(0).getString("deck"))
                val file = AnkiquestDeckCopies.download(account, 7, targetContext.cacheDir)
                try {
                    assertTrue(file.readBytes().contentEquals(packageBytes))
                } finally {
                    file.delete()
                }
                AnkiquestDeckCopies.dismiss(account, 7)
                assertEquals(
                    listOf(
                        "GET Bearer hill-secret /api/deck-copies/hill",
                        "GET Bearer hill-secret /api/deck-copies/hill/7",
                        "DELETE Bearer hill-secret /api/deck-copies/hill/7",
                    ),
                    requests,
                )
            } finally {
                server.stop(0)
            }
        }
}
