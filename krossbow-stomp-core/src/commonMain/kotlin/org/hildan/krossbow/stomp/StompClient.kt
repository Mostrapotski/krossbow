package org.hildan.krossbow.stomp

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.hildan.krossbow.stomp.config.StompConfig
import org.hildan.krossbow.stomp.headers.StompConnectHeaders
import org.hildan.krossbow.websocket.WebSocketClient
import org.hildan.krossbow.websocket.WebSocketSession
import org.hildan.krossbow.websocket.defaultWebSocketClient

/**
 * A STOMP client based on the given web socket implementation.
 * The client is used to connect to the server and create a [StompSession].
 * Then, most of the STOMP interactions are done through the [StompSession].
 */
class StompClient(
    private val webSocketClient: WebSocketClient,
    private val config: StompConfig
) {
    constructor(
        webSocketClient: WebSocketClient = defaultWebSocketClient(),
        configure: StompConfig.() -> Unit = {}
    ) : this(
        webSocketClient = webSocketClient,
        config = StompConfig().apply { configure() }
    )

    /**
     * Connects to the given WebSocket [url] and to the STOMP session, and returns after receiving the CONNECTED frame.
     */
    suspend fun connect(url: String, login: String? = null, passcode: String? = null): StompSession {
        try {
            return withTimeout(config.connectionTimeoutMillis) {
                val wsSession = webSocketClient.connect(url)
                wsSession.stompConnect(url, login, passcode)
            }
        } catch (te: TimeoutCancellationException) {
            throw ConnectionTimeout(config.connectionTimeoutMillis, url, te)
        } catch (e: Exception) {
            throw ConnectionException(url, cause = e)
        }
    }

    private suspend fun WebSocketSession.stompConnect(url: String, login: String?, passcode: String?): StompSession {
        val host = extractHost(url)
        val connectHeaders = StompConnectHeaders(
            host = host,
            login = login,
            passcode = passcode,
            heartBeat = config.heartBeat
        )
        val stompSession = InternalStompSession(config, this)
        stompSession.connect(connectHeaders)
        return stompSession
    }

    private fun extractHost(url: String) = url.substringAfter("://").substringBefore("/").substringBefore(":")
}

/**
 * Exception thrown when the websocket connection + STOMP connection takes too much time.
 */
class ConnectionTimeout(
    val timeoutMillis: Long,
    url: String,
    cause: Exception
) : ConnectionException(url, "Timeout of ${timeoutMillis}ms exceeded when connecting to $url", cause)

/**
 * An exception thrown when something went wrong during the connection.
 */
open class ConnectionException(
    val url: String,
    message: String = "Couldn't connect to STOMP server at $url",
    cause: Throwable?
) : Exception(message, cause)
