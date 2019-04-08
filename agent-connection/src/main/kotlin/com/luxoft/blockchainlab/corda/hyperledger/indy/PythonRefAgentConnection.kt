package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import mu.KotlinLogging
import rx.*
import java.net.URI
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class PythonRefAgentConnection : AgentConnection {
    private val log = KotlinLogging.logger {}

    override fun disconnect() {
        if (getConnectionStatus() == AgentConnectionStatus.AGENT_CONNECTED) {
            webSocket.close()
            connectionStatus = AgentConnectionStatus.AGENT_DISCONNECTED
        }
    }

    /**
     * Connects to an agent's endpoint
     */
    override fun connect(url: String, login: String, password: String): Single<Unit> {
        disconnect()
        return Single.create { observer ->
            try {
                webSocket = AgentWebSocketClient(URI(url), login)
                webSocket.apply {
                    connectBlocking()
                    /**
                     * Check the agent's current state
                     */
                    sendAsJson(StateRequest())
                    receiveMessageOfType<State>(MESSAGE_TYPES.STATE_RESPONSE).subscribe({ stateResponse ->
                        if (!checkUserLoggedIn(stateResponse, login)) {
                            /**
                             * If the agent is not yet initialized, send the "connect" request
                             */
                            sendAsJson(WalletConnect(login, password))
                            /**
                             * The agent will respond with the "state" message which again must be checked
                             */
                            receiveMessageOfType<State>(MESSAGE_TYPES.STATE_RESPONSE).subscribe({ newState ->
                                if (!checkUserLoggedIn(newState, login)) {
                                    log.error { "Unable to connect to Wallet" }
                                    throw AgentConnectionException("Error connecting to $url")
                                } else {
                                    pollAgentWorker.start()
                                    observer.onSuccess(Unit)
                                }
                            }, { e: Throwable -> throw(e) })
                        } else {
                            /**
                             * The agent is already logged in
                             */
                            observer.onSuccess(Unit)
                        }
                    }, { e: Throwable -> throw(e) })
                }
            } catch (e: Throwable) {
                observer.onError(e)
            }
        }
    }

    private var connectionStatus: AgentConnectionStatus = AgentConnectionStatus.AGENT_DISCONNECTED

    override fun getConnectionStatus(): AgentConnectionStatus = connectionStatus

    private lateinit var webSocket : AgentWebSocketClient

    private val indyParties = ConcurrentHashMap<String, IndyPartyConnection>()

    private fun getPubkeyFromInvite(invite: String): String {
        val invEncoded = invite.split("?c_i=")[1]
        val inv = Base64.getDecoder().decode(invEncoded).toString(Charsets.UTF_8)
        val keyList = SerializationUtils.jSONToAny<Map<String, List<String>>>(inv)["recipientKeys"]
        return keyList?.get(0)!!
    }

    /**
     * Returns true, based on the "state" message returned from the agent, if the agent is already logged-in.
     */
    private fun checkUserLoggedIn(stateMessage: State?, userName: String): Boolean {
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

    /**
     * Establishes a connection to remote IndyParty based on the given invite
     */
    override fun acceptInvite(invite: String): Single<IndyPartyConnection> {
        return Single.create { observer ->
            try {
                if (getConnectionStatus() == AgentConnectionStatus.AGENT_CONNECTED) {
                    /**
                     * To accept the invite, first inform the agent with "receive_invite" message
                     */
                    sendAsJson(ReceiveInviteMessage(invite))
                    val pubKey = getPubkeyFromInvite(invite)
                    /**
                     * The agent must respond with "invite_received" message, containing the public key from invite
                     */
                    webSocket.receiveMessageOfType<InviteReceivedMessage>(MESSAGE_TYPES.INVITE_RECEIVED, pubKey).subscribe({ invRcv ->
                        /**
                         * Now instruct the agent to send the connection request with "send_request" message.
                         * The agent builds up the connection request and forwards it to the other IndyParty's endpoint,
                         * recalling the invite by its public key
                         */
                        sendRequest(pubKey)
                        /**
                         * The agent receives the response from the other party and informs the client with "response_received"
                         * message
                         */
                        webSocket.receiveMessageOfType<ObjectNode>(MESSAGE_TYPES.RESPONSE_RECEIVED, pubKey).subscribe({
                            /**
                             * The "response_received" message will contain the other party's DID and endpoint
                             */
                            val theirPubkey = it["history"]["connection"]["DIDDoc"]["publicKey"][0]["publicKeyBase58"].asText()
                            val theirDid = it["their_did"].asText()
                            /**
                             * retrieve my_did from agent's STATE
                             */
                            sendAsJson(StateRequest())
                            webSocket.receiveMessageOfType<ObjectNode>(MESSAGE_TYPES.STATE_RESPONSE).subscribe { stateResponse ->
                                val pairwise = stateResponse["content"]["pairwise_connections"].find { node ->
                                    node["metadata"]["their_vk"].asText() == theirPubkey
                                }
                                if (pairwise != null) {
                                    val indyParty = IndyParty(webSocket, theirDid, invRcv.endpoint, theirPubkey, pairwise["my_did"].asText())
                                    indyParties[theirDid] = indyParty
                                    observer.onSuccess(indyParty)
                                }
                            }
                        }, { e: Throwable -> throw(e) })
                    }, { e: Throwable -> throw(e) })
                } else {
                    throw AgentConnectionException("AgentConnection object has wrong state")
                }
            } catch (e: Throwable) {
                observer.onError(e)
            }
        }
    }

    private fun sendAsJson(obj: Any) = webSocket.sendAsJson(obj)

    /**
     * Retrieves a new invite from the agent
     */
    override fun generateInvite(): Single<String> {
        return Single.create { observer ->
            try {
                /**
                 * to generate the invite, send "generate_invite" message to the agent, and wait for "invite_generated"
                 * which must contain an invite
                 */
                webSocket.sendAsJson(SendMessage(type = MESSAGE_TYPES.GENERATE_INVITE))
                webSocket.receiveMessageOfType<ReceiveInviteMessage>(MESSAGE_TYPES.INVITE_GENERATED).subscribe({ msg ->
                    observer.onSuccess(msg.invite)
                }, { e: Throwable -> throw(e) })
            } catch (e: Throwable) {
                observer.onError(e)
            }
        }
    }

    private val awaitingPairwiseConnections = ConcurrentHashMap<String, SingleSubscriber<in JsonNode>>()
    private val currentPairwiseConnections = ConcurrentHashMap<String, JsonNode>()

    private fun processStateResponse(stateResponse: ObjectNode) {
        stateResponse["content"]["pairwise_connections"].forEach { pairwise ->
            val publicKey = pairwise["metadata"]["connection_key"].asText()
            val observer = awaitingPairwiseConnections.remove(publicKey)
            if (observer != null) {
                pollingCount.decrementAndGet()
                observer.onSuccess(pairwise)
            } else
                currentPairwiseConnections[publicKey] = pairwise
        }
    }

    private fun removeStateObserver(pubKey: String) {
        val observer = awaitingPairwiseConnections.remove(pubKey)
        if( observer != null)
            pollingCount.decrementAndGet()
    }

    private val pollingLock = java.lang.Object()
    private val pollingCount = AtomicInteger(0)
    private fun AtomicInteger.increment() {
        if (getAndIncrement() == 0)
            synchronized(pollingLock) { pollingLock.notify() }
    }
    /**
     * Agent's state polling thread. It resumes whenever [pollingLock] is being notified.
     * It also loops by itself when
     */
    private val pollAgentWorker = thread {
        while (true) {
            /**
             * Sleep until a pairwise connection observer is registered.
             */
            synchronized(pollingLock) { pollingLock.wait() }
            /**
             * Send the request
             */
            sendAsJson(StateRequest())
            webSocket.receiveMessageOfType<ObjectNode>(MESSAGE_TYPES.STATE_RESPONSE).subscribe {
                processStateResponse(it)
                if (pollingCount.get() != 0)
                    synchronized(pollingLock) { pollingLock.notify() }
            }
        }
    }

    private fun waitForPairwiseConnection(pubKey: String): Single<JsonNode> {
        return Single.create { observer ->
            try {
                /**
                 * Check if other callers already parsed the state and it has the key
                 */
                val connection = currentPairwiseConnections.remove(pubKey)
                if (connection != null) {
                    observer.onSuccess(connection)
                } else {
                    /**
                     * Otherwise put the observer in the queue and notify the worker to poll the agent state.
                     * When the worker finds the public key in the parsed state, it will notify the observer.
                     */
                    awaitingPairwiseConnections[pubKey] = observer
                    pollingCount.increment()
                }
            } catch (e: Throwable) {
                observer.onError(e)
            }
        }
    }
    private fun subscribeOnRequestReceived() {
        /**
         * On an incoming connection request, the agent must send the "request_received"
         */
        webSocket.receiveMessageOfType<RequestReceivedMessage>(MESSAGE_TYPES.REQUEST_RECEIVED).subscribe {
            /**
             * On the "request_received" message, reply with "send_response" with remote DID to any incoming connection request.
             * At this stage it doesn't matter which party sent the connection request, because it's not possible to correlate
             * "request_received" message and the invite. For the purpose of PoC we reply with "send_response"
             * on each "request_received", no matter which party is on the other side.
             *
             * TODO: suggest an improvement in pythonic indy-agent that incorporates the invite's public key in the "request" message,
             * TODO: so that it's possible to correlate "request_received" which includes "request" and the invite
             */
            sendAsJson(RequestSendResponseMessage(it.did))
            webSocket.receiveMessageOfType<RequestResponseSentMessage>(MESSAGE_TYPES.RESPONSE_SENT, it.did).subscribe {
                log.info { "Accepted connection request from ${it.did}" }
            }
        }
    }
    /**
     * Waits for incoming connection from whatever party which possesses the invite
     */
    override fun waitForInvitedParty(invite: String): Single<IndyPartyConnection> {
        return Single.create { observer ->
            try {
                if (getConnectionStatus() == AgentConnectionStatus.AGENT_CONNECTED) {
                    /**
                     * Subscribe on "request_received" so to make sure the request is processed when/if it comes.
                     * It's nothing wrong if it doesn't come (for example, it's been processed earlier).
                     */
                    subscribeOnRequestReceived()
                    val pubKey = getPubkeyFromInvite(invite)
                    /**
                     * Wait until the STATE has pairwise connection corresponding to the invite.
                     */
                    waitForPairwiseConnection(pubKey).timeout(60000, TimeUnit.MILLISECONDS).subscribe({ pairwise ->
                        val theirDid = pairwise["their_did"].asText()
                        val indyParty = IndyParty(webSocket, theirDid,
                                pairwise["metadata"]["their_endpoint"].asText(),
                                pairwise["metadata"]["their_vk"].asText(),
                                pairwise["my_did"].asText())
                        indyParties[theirDid] = indyParty
                        observer.onSuccess(indyParty)
                    }, { e ->
                        if (e is TimeoutException) {
                            removeStateObserver(pubKey)
                            throw AgentConnectionException("Remote IndyParty delayed to report to the Agent. Try increasing the timeout.")
                        } else throw e
                    })
                } else {
                    throw AgentConnectionException("Agent is disconnected")
                }
            } catch (e: Throwable) {
                observer.onError(e)
            }
        }
    }

    override fun getIndyPartyConnection(partyDID: String): Single<IndyPartyConnection?> {
        return Single.create { observer ->
            try {
                if (getConnectionStatus() == AgentConnectionStatus.AGENT_CONNECTED) {
                    /**
                     * Query local connection objects associated with the remote party's DID
                     */
                    if (partyDID in indyParties.keys) observer.onSuccess(indyParties[partyDID]) else {
                        /**
                         * If not found, query agent state for the properties of the previously set pairwise connection
                         */
                        sendAsJson(StateRequest())
                        webSocket.receiveMessageOfType<ObjectNode>(MESSAGE_TYPES.STATE_RESPONSE).subscribe { stateResponse ->
                            val pairwise = stateResponse["content"]["pairwise_connections"].find { node ->
                                node["their_did"].asText() == partyDID
                            }
                            if (pairwise != null) {
                                observer.onSuccess(IndyParty(webSocket, partyDID,
                                        pairwise["metadata"]["their_endpoint"].asText(),
                                        pairwise["metadata"]["their_vk"].asText(),
                                        pairwise["my_did"].asText()))
                            } else {
                                observer.onSuccess(null)
                                log.info {
                                    "Remote IndyParty (DID:$partyDID) is unknown to the agent. " +
                                            "Initiate a new connection using generateInvite()/acceptInvite()/waitForInvitedParty()"
                                }
                            }
                        }
                    }
                } else {
                    throw AgentConnectionException("Agent is disconnected")
                }
            } catch (e: Throwable) {
                observer.onError(e)
            }
        }
    }

    private fun sendRequest(key: String) = webSocket.sendAsJson(SendRequestMessage(key))
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
    val MESSAGE_SENT = ADMIN_BASICMESSAGE_BASE + "message_sent"
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
    val RESPONSE_SENT = ADMIN_CONNECTIONS_BASE + "response_sent"
    val REQUEST_SENT = ADMIN_CONNECTIONS_BASE + "request_sent"
    val STATE_REQUEST = ADMIN_BASE + "state_request"
    val STATE_RESPONSE = ADMIN_BASE + "state"
}

data class WalletConnect(val name: String, val passphrase: String, @JsonProperty("@type") val type: String = MESSAGE_TYPES.CONNECT)
data class StateRequest(@JsonProperty("@type") val type: String = MESSAGE_TYPES.STATE_REQUEST)
data class State(@JsonProperty("@type") val type: String = MESSAGE_TYPES.STATE_RESPONSE, val content: Map<String, Any>? = null)
data class ReceiveInviteMessage(val invite: String, val label: String = "", @JsonProperty("@type") val type: String = MESSAGE_TYPES.RECEIVE_INVITE)
data class InviteReceivedMessage(val key: String, val label: String, val endpoint: String, @JsonProperty("@type") val type: String)
data class SendRequestMessage(val key: String, @JsonProperty("@type") val type: String = MESSAGE_TYPES.SEND_REQUEST)
data class RequestReceivedMessage(val label: String, val did: String, val endpoint: String, @JsonProperty("@type") val type: String)
data class RequestSendResponseMessage(val did: String, @JsonProperty("@type") val type: String = MESSAGE_TYPES.SEND_RESPONSE)
data class RequestResponseReceivedMessage(val their_did: String, val history: ObjectNode, @JsonProperty("@type") val type: String)
data class RequestResponseSentMessage(@JsonProperty("@type") val type: String = MESSAGE_TYPES.RESPONSE_SENT, val label: String, val did: String)
data class SendMessage(val to: String? = null, val message: TypedBodyMessage? = null, @JsonProperty("@type") val type: String = MESSAGE_TYPES.SEND_MESSAGE)
data class MessageReceivedMessage(val from: String, val sent_time: String, val content: TypedBodyMessage)
data class MessageReceived(val id: String?, val with: String?, val message: MessageReceivedMessage, @JsonProperty("@type") val type: String = MESSAGE_TYPES.SEND_MESSAGE)
data class LoadMessage(val with: String, @JsonProperty("@type") val type: String = MESSAGE_TYPES.GET_MESSAGES)
data class TypedBodyMessage(val message: Any, @JsonProperty("@class") val clazz: String, val correlationId: String = UUID.randomUUID().toString())