package com.chamelex.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.withTimeoutOrNull

class ChameleonProtocolTest {

    @Test
    fun `same command requests are serialized until their response arrives`() = runBlocking {
        val incomingBytes = Channel<ByteArray>(Channel.UNLIMITED)
        val sentFrames = Channel<ByteArray>(Channel.UNLIMITED)
        val incoming: Flow<ByteArray> = flow {
            for (bytes in incomingBytes) emit(bytes)
        }
        val protocolScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val protocol = ChameleonProtocol(
            send = { sentFrames.send(it) },
            incoming = incoming,
            scope = protocolScope,
        )

        try {
            val firstData = byteArrayOf(0x11)
            val secondData = byteArrayOf(0x22)
            val firstResult = CompletableDeferred<Response>()
            val secondResult = CompletableDeferred<Response>()
            val first = launch {
                firstResult.complete(protocol.sendCommand(Command.GET_APP_VERSION, firstData))
            }
            val firstRequest = FrameCodec.Decoder().feed(sentFrames.receive()).single()
            assertArrayEquals(firstData, firstRequest.data)

            val second = launch {
                secondResult.complete(protocol.sendCommand(Command.GET_APP_VERSION, secondData))
            }
            yield()
            assertTrue(withTimeoutOrNull(50) { sentFrames.receive() } == null)

            incomingBytes.send(
                FrameCodec.encode(Command.GET_APP_VERSION.code, Status.SUCCESS.code, byteArrayOf(1))
            )
            assertArrayEquals(byteArrayOf(1), firstResult.await().data)

            val secondRequest = FrameCodec.Decoder().feed(sentFrames.receive()).single()
            assertArrayEquals(secondData, secondRequest.data)
            incomingBytes.send(
                FrameCodec.encode(Command.GET_APP_VERSION.code, Status.SUCCESS.code, byteArrayOf(2))
            )
            assertArrayEquals(byteArrayOf(2), secondResult.await().data)
            first.join()
            second.join()
        } finally {
            protocolScope.cancel()
            incomingBytes.close()
            sentFrames.close()
        }
    }
}
