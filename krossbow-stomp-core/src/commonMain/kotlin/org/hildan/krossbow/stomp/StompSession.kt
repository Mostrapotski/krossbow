package org.hildan.krossbow.stomp

import kotlinx.coroutines.channels.ReceiveChannel
import org.hildan.krossbow.stomp.config.StompConfig
import org.hildan.krossbow.stomp.frame.FrameBody
import org.hildan.krossbow.stomp.frame.StompFrame
import org.hildan.krossbow.stomp.frame.asText
import org.hildan.krossbow.stomp.headers.StompSendHeaders

/**
 * A coroutine-based STOMP session.
 *
 * ### Suspension & Receipts
 *
 * The STOMP protocol supports RECEIPT frames, allowing the client to know when the server has received a frame.
 * This only happens if a receipt header is set on the client frame.
 * If [auto-receipt][StompConfig.autoReceipt] is enabled, all client frames supporting the mechanism will be
 * automatically given such a header. If auto-receipt is not enabled, a receipt header may be provided manually.
 *
 * When a receipt header is present (automatically added or manually provided), all [send] and [subscribe] overloads
 * suspend until the relevant RECEIPT frame is received from the server. If no RECEIPT frame is received from the server
 * in the configured [time limit][StompConfig.receiptTimeoutMillis], a [LostReceiptException] is thrown.
 *
 * If no receipt is provided and auto-receipt is disabled, the [send] and [subscribe] methods return immediately
 * after the underlying web socket implementation has returned from sending the frame.
 */
interface StompSession {

    /**
     * Sends a SEND frame to the server with the given [headers] and the given [body].
     *
     * @return null right after sending the frame if auto-receipt is disabled and no receipt header is provided.
     * Otherwise this method suspends until the relevant RECEIPT frame is received from the server, and then returns
     * a [StompReceipt].
     * If no RECEIPT frame is received from the server in the configured [time limit][StompConfig.receiptTimeoutMillis],
     * a [LostReceiptException] is thrown.
     */
    suspend fun send(headers: StompSendHeaders, body: FrameBody?): StompReceipt?

    /**
     * Subscribes to the given [destination], converting received messages into objects of type [T] using
     * [convertMessage].
     * The returned [StompSubscription] can be used to access the channel of received objects and unsubscribe.
     *
     * If auto-receipt is enabled or if a non-null [receiptId] is provided, this method suspends until the relevant
     * RECEIPT frame is received from the server.
     * If no RECEIPT frame is received from the server in the configured [time limit][StompConfig.receiptTimeoutMillis],
     * a [LostReceiptException] is thrown.
     *
     * If auto-receipt is disabled and no [receiptId] is provided, this method returns immediately.
     */
    suspend fun <T> subscribe(
        destination: String,
        receiptId: String? = null,
        convertMessage: (StompFrame.Message) -> T
    ): StompSubscription<T>

    /**
     * If [graceful disconnect][StompConfig.gracefulDisconnect] is enabled (which is the default), sends a DISCONNECT
     * frame to close the session, waits for the relevant RECEIPT frame, and then closes the connection. Otherwise,
     * force-closes the connection.
     *
     * If a RECEIPT frame is not received within the [configured time][StompConfig.disconnectTimeoutMillis], this
     * function doesn't throw an exception, it returns normally.
     */
    suspend fun disconnect()
}

/**
 * A STOMP receipt, as
 * [defined in the STOMP specification](https://stomp.github.io/stomp-specification-1.2.html#RECEIPT).
 */
data class StompReceipt(
    /**
     * The value of the receipt header sent to the server, and returned in a RECEIPT frame.
     */
    val id: String
)

/**
 * A subscription to a STOMP destination, streaming messages of type [T].
 */
interface StompSubscription<out T> {
    /**
     * The subscription ID used by the STOMP protocol.
     */
    val id: String

    /**
     * The subscription messages channel, to read incoming messages from.
     */
    val messages: ReceiveChannel<T>

    /**
     * Unsubscribes from this subscription to stop receive messages. This closes the [messages] channel, so that any
     * loop on it stops as well.
     */
    suspend fun unsubscribe()
}

/**
 * Sends a SEND frame to the server at the given [destination] with the given binary [body].
 *
 * @return null right after sending the frame if auto-receipt is disabled.
 * Otherwise this method suspends until the relevant RECEIPT frame is received from the server, and then returns
 * a [StompReceipt].
 * If no RECEIPT frame is received from the server in the configured [time limit][StompConfig.receiptTimeoutMillis],
 * a [LostReceiptException] is thrown.
 */
suspend fun StompSession.sendBinary(destination: String, body: ByteArray?): StompReceipt? =
        send(StompSendHeaders(destination), body?.let { FrameBody.Binary(it) })

/**
 * Sends a SEND frame to the server at the given [destination] with the given textual [body].
 *
 * @return null right after sending the frame if auto-receipt is disabled.
 * Otherwise this method suspends until the relevant RECEIPT frame is received from the server, and then returns
 * a [StompReceipt].
 * If no RECEIPT frame is received from the server in the configured [time limit][StompConfig.receiptTimeoutMillis],
 * a [LostReceiptException] is thrown.
 */
suspend fun StompSession.sendText(destination: String, body: String?): StompReceipt? =
        send(StompSendHeaders(destination), body?.let { FrameBody.Text(it) })

/**
 * Sends a SEND frame to the server at the given [destination] without body.
 *
 * @return null right after sending the frame if auto-receipt is disabled.
 * Otherwise this method suspends until the relevant RECEIPT frame is received from the server, and then returns
 * a [StompReceipt].
 * If no RECEIPT frame is received from the server in the configured [time limit][StompConfig.receiptTimeoutMillis],
 * a [LostReceiptException] is thrown.
 */
suspend fun StompSession.sendEmptyMsg(destination: String): StompReceipt? = send(StompSendHeaders(destination), null)

/**
 * Subscribes to the given [destination], expecting raw message frames.
 * The returned [StompSubscription] can be used to access the channel of received messages and unsubscribe.
 *
 * If auto-receipt is enabled or if a non-null [receiptId] is provided, this method suspends until the relevant
 * RECEIPT frame is received from the server.
 * If no RECEIPT frame is received from the server in the configured [time limit][StompConfig.receiptTimeoutMillis],
 * a [LostReceiptException] is thrown.
 *
 * If auto-receipt is disabled and no [receiptId] is provided, this method returns immediately.
 */
suspend fun StompSession.subscribeRaw(
    destination: String,
    receiptId: String? = null
): StompSubscription<StompFrame.Message> = subscribe(destination, receiptId) { it }

/**
 * Subscribes to the given [destination], expecting text message bodies.
 * The returned [StompSubscription] can be used to access the channel of received messages and unsubscribe.
 * Frames without a body are seen as a null value in the messages channel of the subscription.
 *
 * If auto-receipt is enabled or if a non-null [receiptId] is provided, this method suspends until the relevant
 * RECEIPT frame is received from the server.
 * If no RECEIPT frame is received from the server in the configured [time limit][StompConfig.receiptTimeoutMillis],
 * a [LostReceiptException] is thrown.
 *
 * If auto-receipt is disabled and no [receiptId] is provided, this method returns immediately.
 */
suspend fun StompSession.subscribeText(
    destination: String,
    receiptId: String? = null
): StompSubscription<String?> = subscribe(destination, receiptId) { it.body?.asText() }

/**
 * Subscribes to the given [destination], expecting binary message bodies.
 * The returned [StompSubscription] can be used to access the channel of received messages and unsubscribe.
 * Frames without a body are seen as a null value in the messages channel of the subscription.
 *
 * If auto-receipt is enabled or if a non-null [receiptId] is provided, this method suspends until the relevant
 * RECEIPT frame is received from the server.
 * If no RECEIPT frame is received from the server in the configured [time limit][StompConfig.receiptTimeoutMillis],
 * a [LostReceiptException] is thrown.
 *
 * If auto-receipt is disabled and no [receiptId] is provided, this method returns immediately.
 */
suspend fun StompSession.subscribeBinary(
    destination: String,
    receiptId: String? = null
): StompSubscription<ByteArray?> = subscribe(destination, receiptId) { it.body?.bytes }

/**
 * Subscribes to the given [destination], ignoring the body of the received messages.
 *
 * If auto-receipt is enabled or if a non-null [receiptId] is provided, this method suspends until the relevant
 * RECEIPT frame is received from the server.
 * If no RECEIPT frame is received from the server in the configured [time limit][StompConfig.receiptTimeoutMillis],
 * a [LostReceiptException] is thrown.
 *
 * If auto-receipt is disabled and no [receiptId] is provided, this method returns immediately.
 */
suspend fun StompSession.subscribeEmptyMsg(
    destination: String,
    receiptId: String? = null
): StompSubscription<Unit> = subscribe(destination, receiptId) { Unit }

/**
 * Executes the given block on this [StompSession], and [disconnects][StompSession.disconnect] from the session whether
 * the block terminated normally or exceptionally.
 */
suspend fun <S : StompSession, T> S.use(block: suspend S.() -> T): T {
    try {
        return block()
    } finally {
        disconnect()
    }
}

/**
 * Exception thrown when a STOMP error frame is received.
 */
class StompErrorFrameReceived(val frame: StompFrame.Error) : Exception("STOMP ERROR frame received: ${frame.message}")

/**
 * An exception thrown when a RECEIPT frame was expected from the server, but not received in the configured time limit.
 */
class LostReceiptException(
    /** The value of the receipt header sent to the server, and expected in a RECEIPT frame. */
    val receiptId: String,
    /** THe configured timeout which has expired. */
    val configuredTimeoutMillis: Long,
    /** The frame which did not get acknowledged by the server. */
    val frame: StompFrame
) : Exception("No RECEIPT frame received for receiptId '$receiptId' (in ${frame.command} frame) within ${configuredTimeoutMillis}ms")