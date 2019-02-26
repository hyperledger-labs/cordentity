package com.luxoft.blockchainlab.corda.hyperledger.indy


import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.node.ObjectNode
import com.luxoft.blockchainlab.corda.hyperledger.indy.AgentConnection.MESSAGE_TYPES.CONNECT
import com.luxoft.blockchainlab.corda.hyperledger.indy.AgentConnection.MESSAGE_TYPES.GENERATE_INVITE
import com.luxoft.blockchainlab.corda.hyperledger.indy.AgentConnection.MESSAGE_TYPES.GET_MESSAGES
import com.luxoft.blockchainlab.corda.hyperledger.indy.AgentConnection.MESSAGE_TYPES.INVITE_GENERATED
import com.luxoft.blockchainlab.corda.hyperledger.indy.AgentConnection.MESSAGE_TYPES.INVITE_RECEIVED
import com.luxoft.blockchainlab.corda.hyperledger.indy.AgentConnection.MESSAGE_TYPES.MESSAGE_RECEIVED
import com.luxoft.blockchainlab.corda.hyperledger.indy.AgentConnection.MESSAGE_TYPES.RECEIVE_INVITE
import com.luxoft.blockchainlab.corda.hyperledger.indy.AgentConnection.MESSAGE_TYPES.REQUEST_RECEIVED
import com.luxoft.blockchainlab.corda.hyperledger.indy.AgentConnection.MESSAGE_TYPES.RESPONSE_RECEIVED
import com.luxoft.blockchainlab.corda.hyperledger.indy.AgentConnection.MESSAGE_TYPES.SEND_MESSAGE
import com.luxoft.blockchainlab.corda.hyperledger.indy.AgentConnection.MESSAGE_TYPES.SEND_REQUEST
import com.luxoft.blockchainlab.corda.hyperledger.indy.AgentConnection.MESSAGE_TYPES.SEND_RESPONSE
import com.luxoft.blockchainlab.hyperledger.indy.*
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import mu.KotlinLogging
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.lang.Thread.sleep
import java.net.URI
import java.util.*


data class IndyParty(val did: String, val endpoint: String, val verkey: String? = null)

interface Connection {
    fun getCounterParty(): IndyParty?

    fun sendCredentialOffer(offer: CredentialOffer)
    fun receiveCredentialOffer(): CredentialOffer

    fun sendCredentialRequest(request: CredentialRequestInfo)
    fun receiveCredentialRequest(): CredentialRequestInfo

    fun sendCredential(credential: CredentialInfo)
    fun receiveCredential(): CredentialInfo

    fun sendProofRequest(request: ProofRequest)
    fun receiveProofRequest(): ProofRequest

    fun sendProof(proof: ProofInfo)
    fun receiveProof(): ProofInfo
}

class AgentConnection(val myAgentUrl: String, val invite: String? = null, val userName: String = "user1", val passphrase: String = "test") : Connection {

    private var counterParty: IndyParty? = null

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
    }

    private val webSocket = AgentWebSocketClient(URI(myAgentUrl))

    init {
        webSocket.apply {
            connectBlocking()
            sendJson(WalletConnect(userName, passphrase))
            if (invite != null) {
                sendJson(ReceiveInviteMessage(invite))
                val invite = waitForMessageOfType<InviteReceivedMessage>(INVITE_RECEIVED)
                sendRequest(invite.key)
                val response = waitForMessageOfType<RequestResponseReceivedMessage>(RESPONSE_RECEIVED)
                counterParty = IndyParty(response.their_did, invite.endpoint)
            }
        }
    }

    fun sendJson(obj: Any) = webSocket.sendJson(obj)

    fun genInvite(): ReceiveInviteMessage {
        webSocket.sendJson(SendMessage(type = GENERATE_INVITE))
        return webSocket.waitForMessageOfType<ReceiveInviteMessage>(INVITE_GENERATED)
    }

    fun waitForCounterParty() {
        webSocket.waitForMessageOfType<RequestReceivedMessage>(REQUEST_RECEIVED).also {
            counterParty = IndyParty(it.did, it.endpoint)
            sendJson(RequestSendResponseMessage(it.did))
        }
    }

    fun sendRequest(key: String) = webSocket.sendJson(SendRequestMessage(key))

    override fun getCounterParty(): IndyParty? {
        return counterParty
    }

    override fun sendCredentialOffer(offer: CredentialOffer) = webSocket.sendTypedMessage(offer, counterParty!!)

    override fun receiveCredentialOffer(): CredentialOffer = webSocket.waitForTypedMessage()

    override fun sendCredentialRequest(request: CredentialRequestInfo) = webSocket.sendTypedMessage(request, counterParty!!)

    override fun receiveCredentialRequest(): CredentialRequestInfo = webSocket.waitForTypedMessage()

    override fun sendCredential(credential: CredentialInfo) = webSocket.sendTypedMessage(credential, counterParty!!)

    override fun receiveCredential(): CredentialInfo = webSocket.waitForTypedMessage()
    override fun sendProofRequest(request: ProofRequest) = webSocket.sendTypedMessage(request, counterParty!!)

    override fun receiveProofRequest(): ProofRequest = webSocket.waitForTypedMessage()

    override fun sendProof(proof: ProofInfo) = webSocket.sendTypedMessage(proof, counterParty!!)

    override fun receiveProof(): ProofInfo = webSocket.waitForTypedMessage()
}

class AgentWebSocketClient(serverUri: URI) : WebSocketClient(serverUri) {
    private val log = KotlinLogging.logger {}

    override fun onOpen(handshakedata: ServerHandshake?) {
        log.info { "Connection opened: $handshakedata" }
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        log.info { "Connection closed: $code,$reason,$remote" }
    }

    val receivedMessages = mutableListOf<String>()

    override fun onMessage(message: String?) {
        log.info { "Message: $message" }
        if (message != null)
            synchronized(receivedMessages) { receivedMessages.add(message) }
    }

    override fun onError(ex: Exception?) {
        log.warn(ex) { "Connection error" }
    }

    fun sendJson(obj: Any) = send(SerializationUtils.anyToJSON(obj))

    fun popMessageOfType(type: String): String? {
        synchronized(receivedMessages) {
            val result = receivedMessages.find { SerializationUtils.jSONToAny<Map<String, Any>>(it)["@type"].toString().contentEquals(type) }
            if (result != null)
                receivedMessages.remove(result)

            return result
        }
    }

    fun waitForMessageOfType(type: String): String {
        var messageOfType: String? = null
        while (messageOfType == null) {
            sleep(500)
            messageOfType = popMessageOfType(type)
        }
        return messageOfType
    }

    inline fun <reified T : Any> waitForMessageOfType(type: String): T {
        return SerializationUtils.jSONToAny(waitForMessageOfType(type))
    }

    fun sendTypedMessage(message: TypedBodyMessage, counterParty: IndyParty) = sendJson(SendMessage(counterParty.did, message))
    inline fun <reified T : Any> sendTypedMessage(message: T, counterParty: IndyParty) = sendJson(sendTypedMessage(TypedBodyMessage(message, T::class.java.canonicalName), counterParty))
    inline fun <reified T : Any> popTypedMessage(): T? {
        synchronized(receivedMessages) {
            val message = receivedMessages
                    .filter { SerializationUtils.jSONToAny<Map<String, Any>>(it)["@type"].toString().contentEquals(MESSAGE_RECEIVED) }
                    .find { SerializationUtils.jSONToAny<MessageReceived>(it).message.content.clazz == T::class.java.canonicalName }

            if (message != null) {
                val result = SerializationUtils.jSONToAny<T>(
                        SerializationUtils.anyToJSON(
                                SerializationUtils.jSONToAny<MessageReceived>(message).message.content.message
                        )
                )
                receivedMessages.remove(message)
                return result
            }
            return null
        }
    }

    inline fun <reified T : Any> waitForTypedMessage(): T {
        var messageOfType: T? = null
        while (messageOfType == null) {
            sleep(500)
            messageOfType = popTypedMessage()
        }
        return messageOfType
    }
}

data class WalletConnect(val name: String, val passphrase: String, @JsonProperty("@type") val type: String = CONNECT)
data class ReceiveInviteMessage(val invite: String, val label: String = "", @JsonProperty("@type") val type: String = RECEIVE_INVITE)
data class InviteReceivedMessage(val key: String, val label: String, val endpoint: String, @JsonProperty("@type") val type: String)

data class SendRequestMessage(val key: String, @JsonProperty("@type") val type: String = SEND_REQUEST)
data class RequestReceivedMessage(val label: String, val did: String, val endpoint: String, @JsonProperty("@type") val type: String)
data class RequestSendResponseMessage(val did: String, @JsonProperty("@type") val type: String = SEND_RESPONSE)
data class RequestResponseReceivedMessage(val their_did: String, val history: ObjectNode, @JsonProperty("@type") val type: String)

data class SendMessage(val to: String? = null, val message: TypedBodyMessage? = null, @JsonProperty("@type") val type: String = SEND_MESSAGE)
data class MessageReceivedMessage(val from: String, val timestamp: Number, val content: TypedBodyMessage)
data class MessageReceived(val id: String?, val with: String?, val message: MessageReceivedMessage, @JsonProperty("@type") val type: String = SEND_MESSAGE)
data class LoadMessage(val with: String, @JsonProperty("@type") val type: String = GET_MESSAGES)
data class TypedBodyMessage(val message: Any, @JsonProperty("@class") val clazz: String, val correlationId: String = UUID.randomUUID().toString())