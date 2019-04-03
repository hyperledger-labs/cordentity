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
    private var currentStateResponse: ObjectNode? = null

    private fun processStateResponse(stateResponse: ObjectNode) {
        stateResponse["content"]["pairwise_connections"].forEach { node ->
            val publicKey = node["metadata"]["connection_key"].asText()
            if (publicKey in awaitingPairwiseConnections.keys)
                awaitingPairwiseConnections.remove(publicKey)?.onSuccess(node)
            else
                currentPairwiseConnections[publicKey] = node
        }
        currentStateResponse = stateResponse
    }

    private fun removeStateObserver(pubKey: String) {
        awaitingPairwiseConnections.remove(pubKey)
    }

    private fun waitForStateUpdate(pubKey: String): Single<JsonNode> {
        return Single.create { observer ->
            try {
                /**
                 * Check if other callers already parsed the state and it has the key
                 */
                if (pubKey in currentPairwiseConnections.keys)
                    observer.onSuccess(currentPairwiseConnections.remove(pubKey))
                else {
                    /**
                     * Otherwise put the observer in the queue. When a caller parses the next incoming state, it would
                     * notify the observer.
                     */
                    awaitingPairwiseConnections[pubKey] = observer
                    while (pubKey in awaitingPairwiseConnections.keys) {
                        synchronized(currentPairwiseConnections) {
                            /**
                             * Do synchronized to reduce simultaneous polling from multiple callers.
                             * One thread would send the request, process the response and notify multiple subscribers.
                             *
                             * When acquired the lock, check if the key is still there
                             */
                            if (pubKey in awaitingPairwiseConnections.keys) {
                                sendAsJson(StateRequest())
                                webSocket.receiveMessageOfType<ObjectNode>(MESSAGE_TYPES.STATE_RESPONSE).subscribe {
                                    processStateResponse(it)
                                }
                                /**
                                 * Sleep for a while to reduce polling and to let others do the job
                                 *
                                 * If a caller is locked in the loop for longer time, it's going to be interrupted after timeout.
                                 */
                                Thread.sleep(200)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                observer.onError(e)
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
                     * On an incoming connection request, the agent must send the "request_received"
                     */
                    webSocket.receiveMessageOfType<RequestReceivedMessage>(MESSAGE_TYPES.REQUEST_RECEIVED).subscribe({
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
                        /**
                         * Wait until the STATE has the public key ("connection_key") corresponding to the invite.
                         */
                        val pubKey = getPubkeyFromInvite(invite)
                        waitForStateUpdate(pubKey).timeout(60000, TimeUnit.MILLISECONDS).subscribe({ pairwise ->
                            /**
                             * Wait for "response_sent" from DID (their_did) associated with the invite public key
                             */
                            webSocket.receiveMessageOfType<RequestResponseSentMessage>(MESSAGE_TYPES.RESPONSE_SENT, pairwise["their_did"].asText()).subscribe { responseSent ->
                                val indyParty = IndyParty(webSocket, responseSent.did,
                                        pairwise["metadata"]["their_endpoint"].asText(),
                                        pairwise["metadata"]["their_vk"].asText(),
                                        pairwise["my_did"].asText())
                                indyParties[responseSent.did] = indyParty
                                observer.onSuccess(indyParty)
                            }
                        }, { e ->
                            if (e is TimeoutException) {
                                removeStateObserver(pubKey)
                                throw AgentConnectionException("Remote IndyParty delayed to report to the Agent. Try increasing the timeout.")
                            } else throw e
                        })
                    }, { e: Throwable -> throw(e) })
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