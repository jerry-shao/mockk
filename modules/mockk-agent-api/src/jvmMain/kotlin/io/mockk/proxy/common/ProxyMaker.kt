/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.mockk.proxy.common

import io.mockk.proxy.*
import io.mockk.proxy.common.transformation.InlineInstrumentation
import io.mockk.proxy.common.transformation.SubclassInstrumentation
import io.mockk.proxy.common.transformation.TransformationRequest
import io.mockk.proxy.common.transformation.TransformationType
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

class ProxyMaker(
    private val log: MockKAgentLogger,
    private val inliner: InlineInstrumentation?,
    private val subclasser: SubclassInstrumentation,
    private val instantiator: MockKInstantiatior,
    private val handlers: MutableMap<Any, MockKInvocationHandler>
) : MockKProxyMaker {
    override fun <T : Any> proxy(
        clazz: Class<T>,
        interfaces: Array<Class<*>>,
        handler: MockKInvocationHandler,
        useDefaultConstructor: Boolean,
        instance: Any?
    ): Cancelable<T> {

        throwIfNotPossibleToProxy(clazz, interfaces)

        if (clazz.isInterface) {
            val proxyInstance = Proxy.newProxyInstance(
                clazz.classLoader,
                interfaces + clazz,
                ProxyInvocationHandler(handler)
            )

            return CancelableResult(clazz.cast(proxyInstance))
        }

        val cancellation = inline(clazz)

        val result = CancelableResult<T>(cancelBlock = cancellation)

        val proxyClass = try {
            subclass(clazz, interfaces)
        } catch (ex: Exception) {
            result.cancel()
            throw MockKAgentException("Failed to subclass $clazz", ex)
        }

        try {
            val proxy = instantiate(clazz, proxyClass, useDefaultConstructor, instance)

            subclasser.setProxyHandler(proxy, handler)
            handlers[proxy] = handler
            val callbackRef = WeakReference(proxy)
            return result
                .withValue(proxy)
                .alsoOnCancel {
                    callbackRef.get()?.let {
                        handlers.remove(it)
                    }

                }
        } catch (e: Exception) {
            result.cancel()

            throw MockKAgentException("Instantiation exception", e)
        }
    }

    private fun <T : Any> instantiate(
        clazz: Class<T>,
        proxyClass: Class<T>,
        useDefaultConstructor: Boolean,
        instance: Any?
    ): T {
        return when {
            instance != null -> {
                log.trace("Attaching to object mock for $clazz")
                clazz.cast(instance)
            }
            useDefaultConstructor -> {
                log.trace("Instantiating proxy for $clazz via default constructor")
                clazz.cast(newInstanceViaDefaultConstructor(proxyClass))
            }
            else -> {
                log.trace("Instantiating proxy for $clazz via instantiator")
                instantiator.instance(proxyClass)
            }
        }
    }

    private fun <T : Any> inline(
        clazz: Class<T>
    ): () -> Unit {
        val superclasses = getAllSuperclasses(clazz)

        return if (inliner != null) {
            val transformRequest =
                TransformationRequest(
                    superclasses,
                    TransformationType.SIMPLE
                )

            inliner.execute(transformRequest)
        } else {
            if (!Modifier.isFinal(clazz.modifiers)) {
                warnOnFinalMethods(clazz)
            }

            {}
        }
    }

    private fun <T : Any> subclass(
        clazz: Class<T>,
        interfaces: Array<Class<*>>
    ): Class<T> {
        return if (Modifier.isFinal(clazz.modifiers)) {
            log.trace("Taking instance of $clazz itself because it is final.")
            clazz
        } else {
            log.trace(
                "Building subclass proxy for $clazz with " +
                        "additional interfaces ${interfaces.toList()}"
            )
            subclasser.subclass(clazz, interfaces)
        }
    }

    private fun <T : Any> throwIfNotPossibleToProxy(
        clazz: Class<T>,
        interfaces: Array<Class<*>>
    ) {
        when {
            clazz.isPrimitive ->
                throw MockKAgentException(
                    "Failed to create proxy for $clazz.\n$clazz is a primitive"
                )
            clazz.isArray ->
                throw MockKAgentException(
                    "Failed to create proxy for $clazz.\n$clazz is an array"
                )
            clazz as Class<*> in notMockableClasses ->
                throw MockKAgentException(
                    "Failed to create proxy for $clazz.\n$clazz is one of excluded classes"
                )
            interfaces.isNotEmpty() && Modifier.isFinal(clazz.modifiers) ->
                throw MockKAgentException(
                    "Failed to create proxy for $clazz.\nMore interfaces requested and class is final."
                )
        }
    }

    private fun newInstanceViaDefaultConstructor(cls: Class<*>): Any {
        try {
            val defaultConstructor = cls.getDeclaredConstructor()
            try {
                defaultConstructor.isAccessible = true
            } catch (ex: Exception) {
                // skip
            }

            return defaultConstructor.newInstance()
        } catch (e: Exception) {
            throw MockKAgentException("Default constructor instantiation exception", e)
        }
    }


    private fun warnOnFinalMethods(clazz: Class<*>) {
        for (method in gatherAllMethods(clazz)) {
            val modifiers = method.modifiers
            if (!Modifier.isPrivate(modifiers) && Modifier.isFinal(modifiers)) {
                log.debug(
                    "It is impossible to intercept calls to $method " +
                            "for ${method.declaringClass} because it is final"
                )
            }
        }
    }


    companion object {
        private val notMockableClasses = setOf(
            Class::class.java,
            Boolean::class.java,
            Byte::class.java,
            Short::class.java,
            Char::class.java,
            Int::class.java,
            Long::class.java,
            Float::class.java,
            Double::class.java,
            String::class.java
        )

        private fun gatherAllMethods(clazz: Class<*>): Array<Method> =
            if (clazz.superclass == null) {
                clazz.declaredMethods
            } else {
                gatherAllMethods(clazz.superclass) + clazz.declaredMethods
            }

        private fun getAllSuperclasses(cls: Class<*>): Set<Class<*>> {
            val result = mutableSetOf<Class<*>>()

            var clazz = cls
            while (true) {
                result.add(clazz)
                addInterfaces(result, clazz)
                clazz = clazz.superclass ?: break
            }

            return result
        }

        private fun addInterfaces(result: MutableSet<Class<*>>, clazz: Class<*>) {
            for (intf in clazz.interfaces) {
                if (result.add(intf)) {
                    addInterfaces(result, intf)
                }
            }
        }
    }
}
