package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.fasterxml.jackson.databind.node.ObjectNode
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import mu.KotlinLogging
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import rx.*
import java.net.URI

class AgentWebSocketClient(serverUri: URI, private val socketName: String) : WebSocketClient(serverUri) {
    private val log = KotlinLogging.logger {}

    override fun onOpen(handshakedata: ServerHandshake?) {
        log.info { "$socketName:AgentConnection opened: $handshakedata" }
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        log.info { "$socketName:AgentConnection closed: $code,$reason,$remote" }
    }

    /**
     * inbound messages list
     */
    private val receivedMessages = mutableListOf<String>()
    /**
     * message routing map
     */
    private val subscribedObservers = mutableMapOf<String, MutableList<SingleSubscriber<in String>>>()

    /**
     * Adds an observer to (FIFO) queue corresponding to the given key.
     * If the queue doesn't exist for the given key, it's been created.
     */
    private fun addObserver(key: String, observer: SingleSubscriber<in String>) {
        if (key in subscribedObservers)
            subscribedObservers[key]?.add(observer)
        else
            subscribedObservers[key] = mutableListOf<SingleSubscriber<in String>>().apply { add(observer) }
    }

    /**
     * Removes an observer from the queue.
     * If the queue is empty, it's been released.
     */
    private fun removeObserver(key: String, observer: SingleSubscriber<in String>) {
        if(key in subscribedObservers) {
            val list = subscribedObservers[key]
            list?.remove(observer)
            if (list?.size == 0)
                subscribedObservers.remove(key)
        }
    }

    /**
     * Dispatches the message to an observer.
     * Each message '@type' has own routing agreement
     */
    override fun onMessage(message: String?) {
        log.info { "$socketName:ReceivedMessage: $message" }
        if (message != null) {
            synchronized(receivedMessages) {
                val msg = SerializationUtils.jSONToAny<ObjectNode>(message)
                val type: String = msg["@type"].asText()
                when {
                    type.contentEquals(MESSAGE_TYPES.MESSAGE_RECEIVED) -> {
                        /**
                         * Object messages are routed by the object class name + sender DID
                         */
                        val msgReceived = SerializationUtils.jSONToAny<MessageReceived>(message)
                        val className = msgReceived.message.content.clazz
                        val serializedObject = msgReceived.message.content.message
                        val fromDid = msgReceived.message.from
                        val key = "$className.$fromDid"
                        if (key in subscribedObservers.keys) {
                            /**
                             * select the first object observer from the list, which must be non-empty,
                             * remove the observer from the queue, emit the serialized object
                             */
                            val observer = subscribedObservers[key]!![0]
                            removeObserver(key, observer)
                            observer.onSuccess(SerializationUtils.anyToJSON(serializedObject))
                            return
                        }
                    }
                    type.contentEquals(MESSAGE_TYPES.INVITE_RECEIVED) ->
                        /**
                         * 'invite_received' message is routed by type + public key
                         */
                        msg["@key"] = msg["key"]
                    type.contentEquals(MESSAGE_TYPES.RESPONSE_RECEIVED) ->
                        /**
                         * 'response_received' message is routed by type + public key
                         */
                        msg["@key"] = msg["history"]["connection~sig"]["signer"]
                    type.contentEquals(MESSAGE_TYPES.RESPONSE_SENT) ->
                        /**
                         * 'response_sent' message is routed by type + other party's DID
                         */
                        msg["@key"] = msg["did"]
                }
                val key = msg["@key"]
                val k = if (key == null) type else "$type.${key.asText()}"
                if (k in subscribedObservers.keys) {
                    /**
                     * select the first message observer from the list, which must be non-empty,
                     * remove the observer from the queue, emit the serialized message
                     */
                    val observer = subscribedObservers[k]!![0]
                    removeObserver(k, observer)
                    observer.onSuccess(message)
                    return
                }
                receivedMessages.add(SerializationUtils.anyToJSON(msg))
            }
        }
    }

    override fun onError(ex: Exception?) {
        log.warn(ex) { "AgentConnection error" }
    }

    /**
     * Sends an object (JSON-serialized) to WebSocket
     */
    fun sendJson(obj: Any) {
        val message = SerializationUtils.anyToJSON(obj)
        log.info { "$socketName:SendMessage: $message" }
        send(message)
    }

    /**
     * Receives a message of the given type and key, deserialize it and emit the resulting object
     */
    fun <T : Any> receiveMessageOfType(type: String, key: String? = null, className: Class<T>): Single<T> {
        return Single.create { observer ->
            try {
                popMessageOfType(type, key).subscribe({ message ->
                    val typedMessage: T = SerializationUtils.jSONToAny(message, className)
                    observer.onSuccess(typedMessage)
                }, { e: Throwable -> observer.onError(e) })
            } catch (e: Throwable) {
                observer.onError(e)
            }
        }
    }

    inline fun <reified T : Any> receiveMessageOfType(type: String, key: String? = null): Single<T> = receiveMessageOfType(type, key, T::class.java)

    /**
     * Receives a serialized object from another IndyParty (@from), deserialize it and emit the result
     */
    fun <T : Any> receiveClassObject(className: Class<T>, from: IndyParty): Single<T> {
        return Single.create { observer ->
            try {
                popClassObject(className, from).subscribe({ objectJson ->
                    val classObject: T = SerializationUtils.jSONToAny(objectJson, className)
                    observer.onSuccess(classObject)
                }, { e: Throwable -> observer.onError(e) })
            } catch (e: Throwable) {
                observer.onError(e)
            }
        }
    }

    inline fun <reified T : Any> receiveClassObject(from: IndyParty) = receiveClassObject(T::class.java, from)

    fun sendClassObject(message: TypedBodyMessage, counterParty: IndyParty) = sendJson(SendMessage(counterParty.did, message))

    inline fun <reified T : Any> sendClassObject(message: T, counterParty: IndyParty) = sendClassObject(TypedBodyMessage(message, T::class.java.canonicalName), counterParty)

    /**
     * Subscribes on a message of the given type and key, emits a Single<> serialized message
     */
    private fun popMessageOfType(type: String, key: String? = null): Single<String> {
        return Single.create { observer ->
            try {
                synchronized(receivedMessages) {
                    val result = receivedMessages.find {
                        /**
                         * Check if there are such messages in the queue
                         */
                        SerializationUtils.jSONToAny<Map<String, Any>>(it)["@type"].toString().contentEquals(type) &&
                                (key == null || SerializationUtils.jSONToAny<Map<String, Any>>(it)["@key"].toString().contentEquals(key))
                    }
                    if (result != null) {
                        /**
                         * if found, remove it from the queue and emit the JSON-serialized message
                         */
                        receivedMessages.remove(result)
                        observer.onSuccess(result)
                    } else {
                        /**
                         * otherwise, subscribe on messages of the given type and key
                         */
                        val k = if (key == null) type else "$type.$key"
                        addObserver(k, observer)
                    }
                }
            } catch (e: Throwable) {
                observer.onError(e)
            }
        }
    }

    /**
     * Subscribes on a message containing an object coming from another IndyParty (@from)
     */
    private fun <T : Any> popClassObject(className: Class<T>, from: IndyParty): Single<String> {
        return Single.create { observer ->
            try {
                synchronized(receivedMessages) {
                    val message = receivedMessages
                            .filter { SerializationUtils.jSONToAny<Map<String, Any>>(it)["@type"].toString().contentEquals(MESSAGE_TYPES.MESSAGE_RECEIVED) }
                            .find {
                                /**
                                 * Check if there are such objects in the queue
                                 */
                                SerializationUtils.jSONToAny<MessageReceived>(it).message.content.clazz == className.canonicalName &&
                                        SerializationUtils.jSONToAny<MessageReceived>(it).message.from == from.did
                            }
                    if (message != null) {
                        /**
                         * if found, remove it from the queue and emit the JSON-serialized object
                         */
                        val result = SerializationUtils.anyToJSON(
                                SerializationUtils.jSONToAny<MessageReceived>(message).message.content.message
                        )
                        receivedMessages.remove(message)
                        observer.onSuccess(result)
                    } else {
                        /**
                         * otherwise, subscribe on objects of the given class and origin
                         */
                        addObserver("${className.canonicalName}.${from.did}", observer)
                    }
                }
            } catch (e: Throwable) {
                observer.onError(e)
            }
        }
    }
}

