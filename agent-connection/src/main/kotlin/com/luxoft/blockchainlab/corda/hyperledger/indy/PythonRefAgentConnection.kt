package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.node.ObjectNode
import com.luxoft.blockchainlab.hyperledger.indy.*
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import mu.KotlinLogging
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import rx.Emitter
import rx.schedulers.Schedulers
import rx.Observable
import rx.Observer
import java.net.URI
import java.time.Instant
import java.util.*

class PythonRefAgentConnection : AgentConnection {
    override fun disconnect() {
        if (getConnectionStatus() == AgentConnectionStatus.AGENT_CONNECTED) {
            webSocket.close()
            connectionStatus = AgentConnectionStatus.AGENT_DISCONNECTED
        }
    }

    override fun connect(url: String, login: String, password: String): Observable<Unit> {
        disconnect()
        return Observable.create(
            { observer : Observer<Unit> ->
                try {
                    webSocket = AgentWebSocketClient(URI(url))
                    webSocket.apply {
                        connectBlocking()
                        sendJson(StateRequest())
                        receiveMessageOfType<State>(MESSAGE_TYPES.STATE_RESPONSE).subscribe({ stateResponse ->
                            if (!checkState(stateResponse, login)) {
                                sendJson(WalletConnect(login, password, id = Instant.now().toEpochMilli().toString()))
                                receiveMessageOfType<State>(MESSAGE_TYPES.STATE_RESPONSE).subscribe({ newState ->
                                    if (!checkState(newState, login))
                                        throw AgentConnectionException("Error connecting to $url")
                                    else {
                                        observer.onNext(Unit)
                                    }
                                }, { e: Throwable -> throw(e) }, { observer.onCompleted() })
                            } else {
                                observer.onNext(Unit)
                                observer.onCompleted()
                            }
                        }, { e: Throwable -> throw(e) }, {})
                    }
                } catch (e: Throwable) {
                    observer.onError(e)
                }
            },
            Emitter.BackpressureMode.NONE
        ).subscribeOn(Schedulers.newThread())
    }

    private var connectionStatus: AgentConnectionStatus = AgentConnectionStatus.AGENT_DISCONNECTED

    override fun getConnectionStatus(): AgentConnectionStatus = connectionStatus

    private var counterParty: IndyParty? = null

    private lateinit var webSocket : AgentWebSocketClient

    private fun checkState(stateMessage: State?, userName: String) : Boolean {
        return if(stateMessage != null &&
                stateMessage.content?.get("initialized") == true &&
                stateMessage.content["agent_name"] == userName) {
            connectionStatus = AgentConnectionStatus.AGENT_CONNECTED
            true
        } else {
            connectionStatus = AgentConnectionStatus.AGENT_DISCONNECTED
            false
        }
    }

    override fun acceptInvite(invite: String): Observable<Unit> {
        return Observable.create(
            { observer : Observer<Unit> ->
                try {
                    if (getConnectionStatus() == AgentConnectionStatus.AGENT_CONNECTED) {
                        sendJson(ReceiveInviteMessage(invite))
                        webSocket.receiveMessageOfType<InviteReceivedMessage>(MESSAGE_TYPES.INVITE_RECEIVED).subscribe({ invRcv ->
                            sendRequest(invRcv.key)
                            webSocket.receiveMessageOfType<RequestResponseReceivedMessage>(MESSAGE_TYPES.RESPONSE_RECEIVED).subscribe({
                                counterParty = IndyParty(it.their_did, invRcv.endpoint)
                                observer.onNext(Unit)
                            }, { e: Throwable -> throw(e) }, { observer.onCompleted() })
                        }, { e: Throwable -> throw(e) }, {})
                    } else {
                        throw AgentConnectionException("AgentConnection object has wrong state")
                    }
                } catch (e: Throwable) {
                    observer.onError(e)
                }
            },
            Emitter.BackpressureMode.NONE
        ).subscribeOn(Schedulers.newThread())
    }

    private fun sendJson(obj: Any) = webSocket.sendJson(obj)

    override fun genInvite(): Observable<String> {
        return Observable.create(
            { observer : Observer<String> ->
                try {
                    webSocket.sendJson(SendMessage(type = MESSAGE_TYPES.GENERATE_INVITE, id = Instant.now().toEpochMilli().toString()))
                    webSocket.receiveMessageOfType<ReceiveInviteMessage>(MESSAGE_TYPES.INVITE_GENERATED).subscribe({ msg ->
                        observer.onNext(msg.invite)
                    }, { e: Throwable -> throw(e) }, { observer.onCompleted() })
                } catch (e: Throwable) {
                    observer.onError(e)
                }
            },
            Emitter.BackpressureMode.NONE
        ).subscribeOn(Schedulers.newThread())
    }

    override fun waitForInvitedParty() : Observable<Unit> {
        return Observable.create(
            { observer : Observer<Unit> ->
                try {
                    if (getConnectionStatus() == AgentConnectionStatus.AGENT_CONNECTED) {
                        webSocket.receiveMessageOfType<RequestReceivedMessage>(MESSAGE_TYPES.REQUEST_RECEIVED).subscribe({
                            counterParty = IndyParty(it.did, it.endpoint)
                            sendJson(RequestSendResponseMessage(it.did))
                            observer.onNext(Unit)
                        }, { e: Throwable -> throw(e) }, { observer.onCompleted() })
                    } else {
                        throw AgentConnectionException("Agent is disconnected")
                    }
                } catch (e: Throwable) {
                    observer.onError(e)
                }
            },
            Emitter.BackpressureMode.NONE
        ).subscribeOn(Schedulers.newThread())
    }

    private fun sendRequest(key: String) = webSocket.sendJson(SendRequestMessage(key))

    override fun getCounterParty() = counterParty

    private fun <T : Any> receiveClassObject(className: Class<T>): Observable<T> {
        return Observable.create(
                { observer: Observer<T> ->
                try {
                    webSocket.popClassObject(className).subscribe({ objectJson ->
                        val classObject: T = SerializationUtils.jSONToAny(objectJson, className)
                        observer.onNext(classObject)
                        observer.onCompleted()
                    }, { e: Throwable -> observer.onError(e) }, { observer.onCompleted() })
                } catch (e: Throwable) {
                    observer.onError(e)
                }
            },
            Emitter.BackpressureMode.NONE
        ).subscribeOn(Schedulers.newThread())
    }

    private inline fun <reified T : Any> receiveClassObject() = receiveClassObject(T::class.java)

    override fun sendCredentialOffer(offer: CredentialOffer) = webSocket.sendClassObject(offer, counterParty!!)

    override fun receiveCredentialOffer() = receiveClassObject<CredentialOffer>()

    override fun sendCredentialRequest(request: CredentialRequestInfo) = webSocket.sendClassObject(request, counterParty!!)

    override fun receiveCredentialRequest() = receiveClassObject<CredentialRequestInfo>()

    override fun sendCredential(credential: CredentialInfo) = webSocket.sendClassObject(credential, counterParty!!)

    override fun receiveCredential() = receiveClassObject<CredentialInfo>()

    override fun sendProofRequest(request: ProofRequest) = webSocket.sendClassObject(request, counterParty!!)

    override fun receiveProofRequest() = receiveClassObject<ProofRequest>()

    override fun sendProof(proof: ProofInfo) = webSocket.sendClassObject(proof, counterParty!!)

    override fun receiveProof() = receiveClassObject<ProofInfo>()
}

class AgentWebSocketClient(serverUri: URI) : WebSocketClient(serverUri) {
    private val log = KotlinLogging.logger {}

    override fun onOpen(handshakedata: ServerHandshake?) {
        log.info { "AgentConnection opened: $handshakedata" }
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        log.info { "AgentConnection closed: $code,$reason,$remote" }
    }

    private val receivedMessages = mutableListOf<String>()
    private val subscribedObservers = mutableMapOf<String, Observer<String>>()

    override fun onMessage(message: String?) {
        log.info { "Message: $message" }
        if (message != null) {
            synchronized(receivedMessages) {
                if (SerializationUtils.jSONToAny<Map<String, Any>>(message)["@type"].toString().contentEquals(MESSAGE_TYPES.MESSAGE_RECEIVED)) {
                    val className = SerializationUtils.jSONToAny<MessageReceived>(message).message.content.clazz
                    val messageBody = SerializationUtils.jSONToAny<MessageReceived>(message).message.content.message
                    if (className in subscribedObservers.keys) {
                        val observer = subscribedObservers[className]
                        subscribedObservers.remove(className)
                        observer?.onNext(SerializationUtils.anyToJSON(messageBody))
                        observer?.onCompleted()
                        return
                    }
                } else {
                    val type = SerializationUtils.jSONToAny<Map<String, Any>>(message)["@type"].toString()
                    if (type in subscribedObservers.keys) {
                        val observer = subscribedObservers[type]
                        subscribedObservers.remove(type)
                        observer?.onNext(message)
                        observer?.onCompleted()
                        return
                    }
                }
            }
            synchronized(receivedMessages) { receivedMessages.add(message) }
        }
    }

    override fun onError(ex: Exception?) {
        log.warn(ex) { "AgentConnection error" }
    }

    fun sendJson(obj: Any) = send(SerializationUtils.anyToJSON(obj))

    fun <T : Any> receiveMessageOfType(type: String, className: Class<T>): Observable<T> {
        return Observable.create(
                { observer: Observer<T> ->
                    try {
                        popMessageOfType(type).subscribe({ message ->
                            val typedMessage: T = SerializationUtils.jSONToAny(message, className)
                            observer.onNext(typedMessage)
                        }, { e: Throwable -> observer.onError(e) }, { observer.onCompleted() })
                    } catch (e: Throwable) {
                        observer.onError(e)
                    }
                },
                Emitter.BackpressureMode.NONE
        ).subscribeOn(Schedulers.newThread())
    }

    inline fun <reified T : Any> receiveMessageOfType(type: String): Observable<T> = receiveMessageOfType(type, T::class.java)

    fun sendClassObject(message: TypedBodyMessage, counterParty: IndyParty) = sendJson(SendMessage(counterParty.did, message))

    inline fun <reified T : Any> sendClassObject(message: T, counterParty: IndyParty) = sendClassObject(TypedBodyMessage(message, T::class.java.canonicalName), counterParty)

    private fun popMessageOfType(type: String): Observable<String> {
        return Observable.create(
                { observer: Observer<String> ->
                    try {
                        synchronized(receivedMessages) {
                            val result = receivedMessages.find { SerializationUtils.jSONToAny<Map<String, Any>>(it)["@type"].toString().contentEquals(type) }
                            if (result != null) {
                                receivedMessages.remove(result)
                                observer.onNext(result)
                                observer.onCompleted()
                            } else {
                                if (type in subscribedObservers.keys) {
                                    throw AgentConnectionException("Only 1 subscriber allowed per message type.")
                                } else {
                                    subscribedObservers[type] = observer
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        observer.onError(e)
                    }
                },
                Emitter.BackpressureMode.NONE
        ).subscribeOn(Schedulers.newThread())
    }

    fun <T : Any> popClassObject(className: Class<T>): Observable<String> {
        return Observable.create({ observer: Observer<String> ->
            try {
                synchronized(receivedMessages) {
                    val message = receivedMessages
                            .filter { SerializationUtils.jSONToAny<Map<String, Any>>(it)["@type"].toString().contentEquals(MESSAGE_TYPES.MESSAGE_RECEIVED) }
                            .find { SerializationUtils.jSONToAny<MessageReceived>(it).message.content.clazz == className.canonicalName }

                    if (message != null) {
                        val result = SerializationUtils.anyToJSON(
                                SerializationUtils.jSONToAny<MessageReceived>(message).message.content.message
                                )
                        receivedMessages.remove(message)
                        observer.onNext(result)
                        observer.onCompleted()
                    } else {
                        if (className.canonicalName in subscribedObservers.keys) {
                            throw(AgentConnectionException("Only 1 subscriber allowed per message class."))
                        } else {
                            subscribedObservers[className.canonicalName] = observer
                        }
                    }
                }
            } catch (e: Throwable) {
                observer.onError(e)
            }
        }, Emitter.BackpressureMode.NONE).subscribeOn(Schedulers.newThread())
    }
}

object MESSAGE_TYPES {
    val CONN_BASE = "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/"
    val ADMIN_CONNECTIONS_BASE = "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/admin_connections/1.0/"
    val ADMIN_BASE = "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/admin/1.0/"
    val ADMIN_WALLETCONNECTION_BASE = "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/admin_walletconnection/1.0/"
    val ADMIN_BASICMESSAGE_BASE = "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/admin_basicmessage/1.0/"
    val CONNECT = ADMIN_WALLETCONNECTION_BASE + "connect"
    val DISCONNECT = ADMIN_WALLETCONNECTION_BASE + "disconnect"
    val SEND_MESSAGE = ADMIN_BASICMESSAGE_BASE + "send_message"
    val MESSAGE_RECEIVED = ADMIN_BASICMESSAGE_BASE + "message_received"
    val GET_MESSAGES = ADMIN_BASICMESSAGE_BASE + "get_messages"
    val MESSAGES = ADMIN_BASICMESSAGE_BASE + "messages"
    val GENERATE_INVITE = ADMIN_CONNECTIONS_BASE + "generate_invite"
    val INVITE_GENERATED = ADMIN_CONNECTIONS_BASE + "invite_generated"
    val INVITE_RECEIVED = ADMIN_CONNECTIONS_BASE + "invite_received"
    val RECEIVE_INVITE = ADMIN_CONNECTIONS_BASE + "receive_invite"
    val SEND_REQUEST = ADMIN_CONNECTIONS_BASE + "send_request"
    val REQUEST_RECEIVED = ADMIN_CONNECTIONS_BASE + "request_received"
    val SEND_RESPONSE = ADMIN_CONNECTIONS_BASE + "send_response"
    val RESPONSE_RECEIVED = ADMIN_CONNECTIONS_BASE + "response_received"
    val STATE_REQUEST = ADMIN_BASE + "state_request"
    val STATE_RESPONSE = ADMIN_BASE + "state"
}

data class WalletConnect(val name: String, val passphrase: String, @JsonProperty("@type") val type: String = MESSAGE_TYPES.CONNECT, val id: String? = null)
data class StateRequest(@JsonProperty("@type") val type: String = MESSAGE_TYPES.STATE_REQUEST)
data class State(@JsonProperty("@type") val type: String = MESSAGE_TYPES.STATE_RESPONSE, val content: Map<String, out Any>? = null)
data class ReceiveInviteMessage(val invite: String, val label: String = "", @JsonProperty("@type") val type: String = MESSAGE_TYPES.RECEIVE_INVITE)
data class InviteReceivedMessage(val key: String, val label: String, val endpoint: String, @JsonProperty("@type") val type: String)
data class SendRequestMessage(val key: String, @JsonProperty("@type") val type: String = MESSAGE_TYPES.SEND_REQUEST)
data class RequestReceivedMessage(val label: String, val did: String, val endpoint: String, @JsonProperty("@type") val type: String)
data class RequestSendResponseMessage(val did: String, @JsonProperty("@type") val type: String = MESSAGE_TYPES.SEND_RESPONSE)
data class RequestResponseReceivedMessage(val their_did: String, val history: ObjectNode, @JsonProperty("@type") val type: String)
data class SendMessage(val to: String? = null, val message: TypedBodyMessage? = null, @JsonProperty("@type") val type: String = MESSAGE_TYPES.SEND_MESSAGE, val id: String? = null)
data class MessageReceivedMessage(val from: String, val sent_time: String, val content: TypedBodyMessage)
data class MessageReceived(val id: String?, val with: String?, val message: MessageReceivedMessage, @JsonProperty("@type") val type: String = MESSAGE_TYPES.SEND_MESSAGE)
data class LoadMessage(val with: String, @JsonProperty("@type") val type: String = MESSAGE_TYPES.GET_MESSAGES)
data class TypedBodyMessage(val message: Any, @JsonProperty("@class") val clazz: String, val correlationId: String = UUID.randomUUID().toString())