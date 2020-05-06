package org.hildan.krossbow.stomp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import org.hildan.krossbow.stomp.frame.InvalidStompFrameException
import org.hildan.krossbow.stomp.frame.StompCommand
import org.hildan.krossbow.test.connectWithMocks
import org.hildan.krossbow.test.runAsyncTestWithTimeout
import org.hildan.krossbow.test.simulateErrorFrameReceived
import org.hildan.krossbow.test.simulateMessageFrameReceived
import org.hildan.krossbow.test.waitForSendAndSimulateCompletion
import org.hildan.krossbow.test.waitForSubscribeAndSimulateCompletion
import org.hildan.krossbow.test.waitForUnsubscribeAndSimulateCompletion
import org.hildan.krossbow.websocket.WebSocketCloseCodes
import org.hildan.krossbow.websocket.WebSocketException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StompSessionSubscriptionsTest {

    @Test
    fun subscription_firstOperatorUnsubscribes() = runAsyncTestWithTimeout {
        val (wsSession, stompSession) = connectWithMocks()

        launch {
            val subFrame = wsSession.waitForSubscribeAndSimulateCompletion()
            wsSession.simulateMessageFrameReceived(subFrame.headers.id, "HELLO")
            wsSession.waitForUnsubscribeAndSimulateCompletion(subFrame.headers.id)
            wsSession.waitForSendAndSimulateCompletion(StompCommand.DISCONNECT)
        }
        val messages = stompSession.subscribeText("/dest")
        val message = messages.first()
        assertEquals("HELLO", message)
        assertFalse(wsSession.closed, "Unsubscribe should not close the web socket session")

        stompSession.disconnect()
        assertTrue(wsSession.closed, "disconnect() should close the web socket session")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun subscription_takeOperatorUnsubscribes() = runAsyncTestWithTimeout {
        val (wsSession, stompSession) = connectWithMocks()

        launch {
            val subFrame = wsSession.waitForSubscribeAndSimulateCompletion()
            repeat(3) {
                wsSession.simulateMessageFrameReceived(subFrame.headers.id, "MSG_$it")
            }
            wsSession.waitForUnsubscribeAndSimulateCompletion(subFrame.headers.id)
            wsSession.waitForSendAndSimulateCompletion(StompCommand.DISCONNECT)
        }
        val messagesFlow = stompSession.subscribeText("/dest")
        val messages = messagesFlow.take(3).toList()
        assertEquals(listOf("MSG_0", "MSG_1", "MSG_2"), messages)
        assertFalse(wsSession.closed, "Unsubscribe should not close the web socket session")

        stompSession.disconnect()
        assertTrue(wsSession.closed, "disconnect() should close the web socket session")
    }

    @Test
    fun subscription_collectorCancellationUnsubscribes() = runAsyncTestWithTimeout {
        val (wsSession, stompSession) = connectWithMocks()

        launch {
            val subFrame = wsSession.waitForSubscribeAndSimulateCompletion()
            val job = launch {
                repeat(15) {
                    wsSession.simulateMessageFrameReceived(subFrame.headers.id, "MSG_$it")
                    delay(300)
                }
            }
            wsSession.waitForUnsubscribeAndSimulateCompletion(subFrame.headers.id)
            job.cancel()
            wsSession.waitForSendAndSimulateCompletion(StompCommand.DISCONNECT)
        }
        val messagesFlow = stompSession.subscribeText("/dest")

        val messages = mutableListOf<String>()
        val collectingJob = launch {
            messagesFlow.collect {
                messages.add(it)
            }
        }
        delay(800)
        collectingJob.cancelAndJoin() // joining actually waits for UNSUBSCRIBE

        assertEquals(listOf("MSG_0", "MSG_1", "MSG_2"), messages)
        assertFalse(wsSession.closed, "Unsubscribe should not close the web socket session")

        stompSession.disconnect()
        assertTrue(wsSession.closed, "disconnect() should close the web socket session")
    }

    @Test
    fun subscription_disconnectDoesntNeedToUnsubscribe() = runAsyncTestWithTimeout {
        val (wsSession, stompSession) = connectWithMocks()

        launch {
            val subFrame = wsSession.waitForSubscribeAndSimulateCompletion()
            val job = launch {
                repeat(15) {
                    wsSession.simulateMessageFrameReceived(subFrame.headers.id, "MSG_$it")
                    delay(300)
                }
            }
            wsSession.waitForSendAndSimulateCompletion(StompCommand.DISCONNECT)
            job.cancel()
            // after disconnecting, we should not attempt to send an UNSUBSCRIBE frame
        }
        val messagesFlow = stompSession.subscribeText("/dest")

        val messages = mutableListOf<String>()
        val collectingJob = launch {
            messagesFlow.collect {
                messages.add(it)
            }
        }
        delay(800)
        stompSession.disconnect()

        assertEquals(listOf("MSG_0", "MSG_1", "MSG_2"), messages)
        assertTrue(wsSession.closed, "disconnect() should close the web socket session")
        collectingJob.join()
        assertTrue(collectingJob.isCompleted, "The collector's job should be completed after disconnect")
        assertFalse(collectingJob.isCancelled, "The collector's job should be completed normally, not cancelled")
    }

    @Test
    fun subscription_stompErrorFrame_shouldGiveExceptionInCollector() = runAsyncTestWithTimeout {
        val (wsSession, stompSession) = connectWithMocks()

        val errorMessage = "some error message"
        launch {
            wsSession.waitForSubscribeAndSimulateCompletion()
            wsSession.simulateErrorFrameReceived(errorMessage)
            // after receiving a STOMP error frame, we should not attempt to send an UNSUBSCRIBE or DISCONNECT frame
        }

        val messages = stompSession.subscribeText("/dest")
        val exception = assertFailsWith(StompErrorFrameReceived::class) {
            messages.first()
        }
        assertEquals(errorMessage, exception.frame.message,
            "The exception in collectors should have the STOMP ERROR frame's body as message")
        assertTrue(wsSession.closed, "The web socket should be closed after a STOMP ERROR frame")
    }

    @Test
    fun subscription_webSocketError_shouldGiveExceptionInCollector() = runAsyncTestWithTimeout {
        val (wsSession, stompSession) = connectWithMocks()

        val errorMessage = "some error message"
        launch {
            wsSession.waitForSubscribeAndSimulateCompletion()
            wsSession.simulateError(errorMessage)
            // after a web socket error, we should not attempt to send an UNSUBSCRIBE or DISCONNECT frame
        }

        val messages = stompSession.subscribeText("/dest")
        val exception = assertFailsWith(WebSocketException::class) {
            messages.first()
        }
        assertEquals(errorMessage, exception.message,
            "The exception in collectors should have the web socket error frame's body as message")
        assertTrue(wsSession.closed, "The web socket should be closed after a web socket frame")
    }

    @Test
    fun subscription_webSocketClose_shouldGiveExceptionInCollector() = runAsyncTestWithTimeout {
        val (wsSession, stompSession) = connectWithMocks()

        launch {
            wsSession.waitForSubscribeAndSimulateCompletion()
            wsSession.simulateClose(WebSocketCloseCodes.NORMAL_CLOSURE, "some reason")
            // after a web socket closure, we should not attempt to send an UNSUBSCRIBE or DISCONNECT frame
        }

        val sub = stompSession.subscribeText("/dest")
        val exception = assertFailsWith(WebSocketClosedUnexpectedly::class) {
            sub.first()
        }
        assertEquals(WebSocketCloseCodes.NORMAL_CLOSURE, exception.code)
        assertEquals("some reason", exception.reason)
    }

    @Test
    fun subscription_frameDecodingError_shouldGiveExceptionInCollector() = runAsyncTestWithTimeout {
        val (wsSession, stompSession) = connectWithMocks()

        launch {
            wsSession.waitForSubscribeAndSimulateCompletion()
            wsSession.simulateTextFrameReceived("not a valid STOMP frame")
            // after an invalid STOMP frame, we should not attempt to send an UNSUBSCRIBE or DISCONNECT frame
        }

        val sub = stompSession.subscribeText("/dest")
        assertFailsWith(InvalidStompFrameException::class) {
            sub.first()
        }
        assertTrue(wsSession.closed, "The web socket should be closed after an invalid STOMP frame is received")
    }
}
