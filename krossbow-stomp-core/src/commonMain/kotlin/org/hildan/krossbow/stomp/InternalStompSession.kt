package org.hildan.krossbow.stomp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.hildan.krossbow.stomp.config.StompConfig
import org.hildan.krossbow.stomp.frame.FrameBody
import org.hildan.krossbow.stomp.frame.StompCommand
import org.hildan.krossbow.stomp.frame.StompFrame
import org.hildan.krossbow.stomp.headers.AckMode
import org.hildan.krossbow.stomp.headers.StompAckHeaders
import org.hildan.krossbow.stomp.headers.StompConnectHeaders
import org.hildan.krossbow.stomp.headers.StompDisconnectHeaders
import org.hildan.krossbow.stomp.headers.StompNackHeaders
import org.hildan.krossbow.stomp.headers.StompSendHeaders
import org.hildan.krossbow.stomp.headers.StompSubscribeHeaders
import org.hildan.krossbow.stomp.headers.StompUnsubscribeHeaders
import org.hildan.krossbow.utils.SuspendingAtomicInt
import org.hildan.krossbow.utils.getStringAndInc
import org.hildan.krossbow.websocket.WebSocketSession

@OptIn(ExperimentalCoroutinesApi::class) // for broadcast channel
internal class InternalStompSession(
    private val config: StompConfig,
    webSocketSession: WebSocketSession
) : StompConnection(webSocketSession), StompSession {

    private val nextSubscriptionId = SuspendingAtomicInt(0)

    private val nextReceiptId = SuspendingAtomicInt(0)

    private val subscriptionsById: MutableMap<String, InternalSubscription<*>> = mutableMapOf()

    private val nonMsgFrames = BroadcastChannel<StompFrame>(Channel.BUFFERED)

    override suspend fun onStompFrameReceived(frame: StompFrame) {
        when (frame) {
            is StompFrame.Message -> onMessageFrameReceived(frame)
            is StompFrame.Error -> {
                nonMsgFrames.send(frame)
                shutdown(StompErrorFrameReceived(frame))
            }
            else -> nonMsgFrames.send(frame)
        }
    }

    private suspend fun onMessageFrameReceived(frame: StompFrame.Message) {
        val subId = frame.headers.subscription
        // ignore if subscription not found, maybe we just unsubscribed and received one more msg
        subscriptionsById[subId]?.onMessage(frame)
    }

    internal suspend fun connect(headers: StompConnectHeaders): StompFrame.Connected = coroutineScope {
        val futureConnectedFrame = async {
            waitForTypedFrame<StompFrame.Connected>()
        }
        sendStompFrame(StompFrame.Connect(headers))
        futureConnectedFrame.await()
    }

    private suspend inline fun <reified T : StompFrame> waitForTypedFrame(predicate: (T) -> Boolean = { true }): T {
        val frameSubscription = nonMsgFrames.openSubscription()
        try {
            for (f in frameSubscription) {
                if (f is StompFrame.Error) {
                    throw StompErrorFrameReceived(f)
                }
                if (f is T && predicate(f)) {
                    return f
                }
            }
        } finally {
            frameSubscription.cancel()
        }
        throw IllegalStateException("Frames channel closed unexpectedly while expecting a frame of type ${T::class}")
    }

    override suspend fun send(headers: StompSendHeaders, body: FrameBody?): StompReceipt? {
        if (headers.contentLength == null) {
            headers.contentLength = body?.bytes?.size ?: 0
        }
        return prepareReceiptAndSendFrame(StompFrame.Send(headers, body))
    }

    private suspend fun prepareReceiptAndSendFrame(frame: StompFrame): StompReceipt? {
        val receiptId = getReceiptAndMaybeSetAuto(frame)
        if (receiptId == null) {
            sendStompFrame(frame)
            return null
        }
        sendAndWaitForReceipt(receiptId, frame)
        return StompReceipt(receiptId)
    }

    private suspend fun getReceiptAndMaybeSetAuto(frame: StompFrame): String? {
        if (config.autoReceipt && frame.headers.receipt == null) {
            frame.headers.receipt = nextReceiptId.getStringAndInc()
        }
        return frame.headers.receipt
    }

    private suspend fun sendAndWaitForReceipt(receiptId: String, frame: StompFrame) {
        coroutineScope {
            val deferredReceipt = async { waitForReceipt(receiptId) }
            sendStompFrame(frame)
            withTimeoutOrNull(frame.receiptTimeout) { deferredReceipt.await() }
                ?: throw LostReceiptException(receiptId, frame.receiptTimeout, frame)
        }
    }

    private suspend fun waitForReceipt(receiptId: String): StompFrame.Receipt =
            waitForTypedFrame { it.headers.receiptId == receiptId }

    private val StompFrame.receiptTimeout: Long
        get() = if (command == StompCommand.DISCONNECT) {
            config.disconnectTimeoutMillis
        } else {
            config.receiptTimeoutMillis
        }

    override suspend fun <T> subscribe(
        destination: String,
        receiptId: String?,
        ackMode: AckMode?,
        convertMessage: (StompFrame.Message) -> T
    ): StompSubscription<T> {
        val id = nextSubscriptionId.getAndIncrement().toString()
        val sub = InternalSubscription(id, convertMessage, this)
        subscriptionsById[id] = sub
        val headers = StompSubscribeHeaders(
            destination = destination,
            id = id,
            ack = ackMode ?: AckMode.AUTO
        )
        headers.receipt = receiptId
        val subscribeFrame = StompFrame.Subscribe(headers)
        prepareReceiptAndSendFrame(subscribeFrame)
        return sub
    }

    internal suspend fun unsubscribe(subscriptionId: String) {
        sendStompFrame(StompFrame.Unsubscribe(StompUnsubscribeHeaders(id = subscriptionId)))
        subscriptionsById.remove(subscriptionId)
    }

    override suspend fun ack(headers: StompAckHeaders) {
        sendStompFrame(StompFrame.Ack(headers))
    }

    override suspend fun nack(headers: StompNackHeaders) {
        sendStompFrame(StompFrame.Nack(headers))
    }

    override suspend fun disconnect() {
        if (config.gracefulDisconnect) {
            sendDisconnectFrameAndWaitForReceipt()
        }
        shutdown()
    }

    private suspend fun sendDisconnectFrameAndWaitForReceipt() {
        try {
            val receiptId = nextReceiptId.getStringAndInc()
            val disconnectFrame = StompFrame.Disconnect(StompDisconnectHeaders(receiptId))
            sendAndWaitForReceipt(receiptId, disconnectFrame)
        } catch (e: LostReceiptException) {
            // Sometimes the server closes the connection too quickly to send a RECEIPT, which is not really an error
            // http://stomp.github.io/stomp-specification-1.2.html#Connection_Lingering
        }
    }

    override suspend fun shutdown(cause: Throwable?) {
        subscriptionsById.values.forEach { it.close(cause) }
        subscriptionsById.clear()
        nonMsgFrames.close(cause)
        super.shutdown(cause)
    }
}
