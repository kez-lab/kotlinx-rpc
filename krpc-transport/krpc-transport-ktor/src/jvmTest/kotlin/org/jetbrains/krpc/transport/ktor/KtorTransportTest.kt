@file:Suppress("ExtractKtorModule")

package org.jetbrains.krpc.transport.ktor

import io.ktor.client.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.*
import org.jetbrains.krpc.RPC
import org.jetbrains.krpc.serialization.json
import org.jetbrains.krpc.transport.ktor.client.rpc
import org.jetbrains.krpc.transport.ktor.client.rpcConfig
import org.jetbrains.krpc.transport.ktor.server.rpc
import org.junit.Assert.assertEquals
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test

interface NewService : RPC {
    suspend fun echo(value: String): String
}

class NewServiceImpl : NewService {
    override val coroutineContext: CoroutineContext = Job()

    override suspend fun echo(value: String): String {
        return value
    }
}

class KtorTransportTest {

    @Test
    fun testEcho() = runBlocking {
        val server = embeddedServer(Netty, port = 4242) {
            install(WebSockets)
            routing {
                rpc<NewService>("/rpc", NewServiceImpl()) {
                    serialization {
                        json()
                    }
                }
            }
        }.start()

        val client = HttpClient {
            install(io.ktor.client.plugins.websocket.WebSockets)
        }

        val service = client.rpc<NewService>("ws://localhost:4242/rpc") {
            rpcConfig {
                serialization {
                    json()
                }
            }
        }
        val actual = service.echo("Hello, world!")

        assertEquals("Hello, world!", actual)

        service.cancel()

        server.stop()
        client.close()
    }
}