/*
 * Copyright 2023-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.rpc.krpc.server.internal

import kotlinx.coroutines.*
import kotlinx.rpc.RemoteService
import kotlinx.rpc.descriptor.RpcInvokator
import kotlinx.rpc.descriptor.RpcServiceDescriptor
import kotlinx.rpc.internal.utils.map.ConcurrentHashMap
import kotlinx.rpc.krpc.KrpcConfig
import kotlinx.rpc.krpc.callScoped
import kotlinx.rpc.krpc.internal.*
import kotlinx.rpc.krpc.internal.logging.CommonLogger
import kotlinx.rpc.krpc.streamScopeOrNull
import kotlinx.rpc.krpc.withServerStreamScope
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.StringFormat
import kotlin.coroutines.CoroutineContext

internal class KrpcServerService<T : RemoteService>(
    private val service: T,
    private val descriptor: RpcServiceDescriptor<T>,
    override val config: KrpcConfig.Server,
    private val connector: KrpcServerConnector,
    coroutineContext: CoroutineContext,
) : KrpcServiceHandler(), CoroutineScope {
    override val logger = CommonLogger.logger(objectId(descriptor.fqName))
    override val sender: KrpcMessageSender get() = connector
    private val scope: CoroutineScope = this

    override val coroutineContext: CoroutineContext = coroutineContext.withServerStreamScope()

    private val requestMap = ConcurrentHashMap<String, RpcRequest>()

    init {
        coroutineContext.job.invokeOnCompletion {
            logger.trace { "Service completed with $it" }
        }
    }

    suspend fun accept(message: KrpcCallMessage) {
        val result = runCatching {
            processMessage(message)
        }

        if (result.isFailure) {
            val exception = result.exceptionOrNull()
                ?: error("Expected exception value")

            cancelRequest(
                callId = message.callId,
                message = "Cancelled after failed to process message: $message, error message: ${exception.message}",
                cause = exception,
            )

            if (exception is CancellationException) {
                return
            }

            val error = serializeException(exception)
            val errorMessage = KrpcCallMessage.CallException(
                callId = message.callId,
                serviceType = message.serviceType,
                cause = error,
                connectionId = message.connectionId,
            )

            sender.sendMessage(errorMessage)
        }
    }

    private suspend fun processMessage(message: KrpcCallMessage) {
        logger.trace { "Incoming message $message" }

        when (message) {
            is KrpcCallMessage.CallData -> {
                handleCall(message)
            }

            is KrpcCallMessage.CallException -> {
                cancelRequest(
                    callId = message.callId,
                    message = "Cancelled after KrpcCallMessage.CallException received",
                    cause = message.cause.deserialize(),
                )
            }

            is KrpcCallMessage.CallSuccess -> {
                error("Unexpected success message: $message")
            }

            is KrpcCallMessage.StreamCancel -> {
                // if no stream is present, it probably was already canceled
                getAndAwaitStreamContext(message)
                    ?.cancelStream(message)
            }

            is KrpcCallMessage.StreamFinished -> {
                // if no stream is present, it probably was already finished
                getAndAwaitStreamContext(message)
                    ?.closeStream(message)
            }

            is KrpcCallMessage.StreamMessage -> {
                requestMap[message.callId]?.streamContext?.apply {
                    awaitInitialized().send(message, prepareSerialFormat(this))
                } ?: error("Invalid request call id: ${message.callId}")
            }
        }
    }

    private suspend fun getAndAwaitStreamContext(message: KrpcCallMessage): KrpcStreamContext? {
        return requestMap[message.callId]?.streamContext?.awaitInitialized()
    }

    @Suppress("detekt.ThrowsCount", "detekt.LongMethod")
    private fun handleCall(callData: KrpcCallMessage.CallData) {
        val callId = callData.callId

        val streamContext = LazyKrpcStreamContext(streamScopeOrNull(scope)) {
            KrpcStreamContext(callId, config, callData.connectionId, callData.serviceId, it)
        }
        val serialFormat = prepareSerialFormat(streamContext)

        val isMethod = when (callData.callType) {
            KrpcCallMessage.CallType.Method -> true
            KrpcCallMessage.CallType.Field -> false
            else -> callData.callableName
                .endsWith("\$method") // compatibility with beta-4.2 clients
        }

        val callableName = callData.callableName
            .substringBefore('$') // compatibility with beta-4.2 clients

        val callable = descriptor.getCallable(callableName)

        if (callable == null || callable.invokator is RpcInvokator.Method && !isMethod) {
            val callType = if (isMethod) "method" else "field"
            error("Service ${descriptor.fqName} has no $callType '$callableName'")
        }

        val data = if (isMethod) {
            val serializerModule = serialFormat.serializersModule
            val paramsSerializer = serializerModule.rpcSerializerForType(callable.dataType)
            decodeMessageData(serialFormat, paramsSerializer, callData)
        } else {
            null
        }

        var failure: Throwable? = null

        val requestJob = launch(start = CoroutineStart.LAZY) {
            val result = try {
                val value = when (val invokator = callable.invokator) {
                    is RpcInvokator.Method -> {
                        callScoped(callId) {
                            invokator.call(service, data)
                        }
                    }

                    is RpcInvokator.Field -> {
                        invokator.call(service)
                    }
                }

                val returnType = callable.returnType
                val returnSerializer = serialFormat.serializersModule.rpcSerializerForType(returnType)
                when (serialFormat) {
                    is StringFormat -> {
                        val stringValue = serialFormat.encodeToString(returnSerializer, value)
                        KrpcCallMessage.CallSuccessString(
                            callId = callData.callId,
                            serviceType = descriptor.fqName,
                            data = stringValue,
                            connectionId = callData.connectionId,
                            serviceId = callData.serviceId,
                        )
                    }

                    is BinaryFormat -> {
                        val binaryValue = serialFormat.encodeToByteArray(returnSerializer, value)
                        KrpcCallMessage.CallSuccessBinary(
                            callId = callData.callId,
                            serviceType = descriptor.fqName,
                            data = binaryValue,
                            connectionId = callData.connectionId,
                            serviceId = callData.serviceId,
                        )
                    }

                    else -> {
                        unsupportedSerialFormatError(serialFormat)
                    }
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (@Suppress("detekt.TooGenericExceptionCaught") cause: Throwable) {
                failure = cause

                val serializedCause = serializeException(cause)
                KrpcCallMessage.CallException(
                    callId = callId,
                    serviceType = descriptor.fqName,
                    cause = serializedCause,
                    connectionId = callData.connectionId,
                    serviceId = callData.serviceId,
                )
            }

            sender.sendMessage(result)

            if (failure == null) {
                streamContext.valueOrNull?.apply {
                    launchIf({ incomingHotFlowsAvailable }) {
                        handleIncomingHotFlows(it)
                    }

                    launchIf({ outgoingStreamsAvailable }) {
                        handleOutgoingStreams(it, serialFormat, descriptor.fqName)
                    }
                } ?: run {
                    cancelRequest(callId, fromJob = true)
                }
            } else {
                cancelRequest(callId, "Server request failed", failure, fromJob = true)
            }
        }

        requestMap[callId] = RpcRequest(requestJob, streamContext)

        requestJob.start()
    }

    suspend fun cancelRequest(
        callId: String,
        message: String? = null,
        cause: Throwable? = null,
        fromJob: Boolean = false,
    ) {
        requestMap.remove(callId)?.cancelAndClose(callId, message, cause, fromJob)

        // acknowledge the cancellation
        sender.sendMessage(
            KrpcGenericMessage(
                connectionId = null,
                pluginParams = mapOf(
                    KrpcPluginKey.GENERIC_MESSAGE_TYPE to KrpcGenericMessage.CANCELLATION_TYPE,
                    KrpcPluginKey.CANCELLATION_TYPE to CancellationType.CANCELLATION_ACK.toString(),
                    KrpcPluginKey.CANCELLATION_ID to callId,
                )
            )
        )
    }
}

private class RpcRequest(val handlerJob: Job, val streamContext: LazyKrpcStreamContext) {
    suspend fun cancelAndClose(
        callId: String,
        message: String? = null,
        cause: Throwable? = null,
        fromJob: Boolean = false,
    ) {
        if (!handlerJob.isCompleted && !fromJob) {
            when {
                message != null && cause != null -> handlerJob.cancel(message, cause)
                message != null -> handlerJob.cancel(message)
                else -> handlerJob.cancel()
            }

            handlerJob.join()
        }

        val ctx = streamContext.valueOrNull
        if (ctx == null) {
            streamContext.streamScopeOrNull
                ?.cancelRequestScopeById(callId, message ?: "Scope cancelled", cause)
                ?.join()
        } else {
            ctx.cancel(message ?: "Request cancelled", cause)?.join()
        }
    }
}