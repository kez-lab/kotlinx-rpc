/*
 * Copyright 2023-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.rpc

/**
 * The field marked with this annotation will be initialized with the service creation.
 */
@Target(AnnotationTarget.PROPERTY)
public annotation class RpcEagerField

@Deprecated("Use RpcEagerField instead", ReplaceWith("RpcEagerField"), level = DeprecationLevel.ERROR)
public typealias RPCEagerField = RpcEagerField