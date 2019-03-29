package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.node.ObjectNode
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import mu.KotlinLogging
import rx.Single
import java.net.URI
import java.util.*

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
                    sendJson(StateRequest())
                    receiveMessageOfType<State>(MESSAGE_TYPES.STATE_RESPONSE).subscribe({ stateResponse ->
                        if (!checkState(stateResponse, login)) {
                            /**
                             * If the agent is not yet initialized, send the "connect" request
                             */
                            sendJson(WalletConnect(login, password))
                            /**
                             * The agent will respond with the "state" message which again must be checked
                             */
                            receiveMessageOfType<State>(MESSAGE_TYPES.STATE_RESPONSE).subscribe({ newState ->
                                if (!checkState(newState, login))
                                    throw AgentConnectionException("Error connecting to $url")
                                else {
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

    private val indyParties = HashMap<String, IndyPartyConnection>()

    private fun getPubkeyFromInvite(invite: String): String {
        val invEncoded = invite.split("?c_i=")[1]
        val inv = Base64.getDecoder().decode(invEncoded).toString(Charsets.UTF_8)
        val keyList = SerializationUtils.jSONToAny<Map<String, List<String>>>(inv)["recipientKeys"]
        return keyList?.get(0)!!
    }

    /**
     * Returns true, based on the "state" message returned from the agent, if the agent is already logged-in.
     */
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
                    sendJson(ReceiveInviteMessage(invite))
                    val pKey = getPubkeyFromInvite(invite)
                    /**
                     * The agent must respond with "invite_received" message, containing the public key from invite
                     */
                    webSocket.receiveMessageOfType<InviteReceivedMessage>(MESSAGE_TYPES.INVITE_RECEIVED, pKey).subscribe({ invRcv ->
                        /**
                         * Now instruct the agent to send the connection request with "send_request" message.
                         * The agent builds up the connection request and forwards it to the other IndyParty's endpoint,
                         * recalling the invite by its public key
                         */
                        sendRequest(invRcv.key)
                        /**
                         * The agent receives the response from the other party and informs the client with "response_received"
                         * message
                         */
                        webSocket.receiveMessageOfType<ObjectNode>(MESSAGE_TYPES.RESPONSE_RECEIVED, pKey).subscribe({
                            /**
                             * The "response_received" message will contain the other party's DID and endpoint
                             */
                            val theirPubkey = it["history"]["connection"]["DIDDoc"]["publicKey"][0]["publicKeyBase58"].asText()
                            val theirDid = it["their_did"].asText()
                            /**
                             * retrieve my_did from agent's STATE
                             */
                            sendJson(StateRequest())
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

    private fun sendJson(obj: Any) = webSocket.sendJson(obj)

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
                webSocket.sendJson(SendMessage(type = MESSAGE_TYPES.GENERATE_INVITE))
                webSocket.receiveMessageOfType<ReceiveInviteMessage>(MESSAGE_TYPES.INVITE_GENERATED).subscribe({ msg ->
                    observer.onSuccess(msg.invite)
                }, { e: Throwable -> throw(e) })
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
                         * on the "request_received" message, reply with "send_response" with remote DID to any incoming connection request.
                         * at this stage it doesn't matter which party sent the connection request
                         */
                        sendJson(RequestSendResponseMessage(it.did))
                        /**
                         * Check if the STATE already has the public key ("connection_key") corresponding to the invite
                         */
                        sendJson(StateRequest())
                        webSocket.receiveMessageOfType<ObjectNode>(MESSAGE_TYPES.STATE_RESPONSE).subscribe { stateResponse ->
                            val pairwise = stateResponse["content"]["pairwise_connections"].find { node ->
                                node["metadata"]["connection_key"].asText() == getPubkeyFromInvite(invite)
                            }
                            if (pairwise != null) {
                                /**
                                 * if so, wait for "response_sent" from DID (their_did) associated with the invite public key
                                 */
                                webSocket.receiveMessageOfType<RequestResponseSentMessage>(MESSAGE_TYPES.RESPONSE_SENT, pairwise["their_did"].asText()).subscribe { responseSent ->
                                    val indyParty = IndyParty(webSocket, responseSent.did,
                                            pairwise["metadata"]["their_endpoint"].asText(),
                                            pairwise["metadata"]["their_vk"].asText(),
                                            pairwise["my_did"].asText())
                                    indyParties[responseSent.did] = indyParty
                                    observer.onSuccess(indyParty)
                                }
                            } else
                                throw AgentConnectionException("Remote IndyParty delayed to report to the Agent. Try refreshing the state.")
                        }
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
                        sendJson(StateRequest())
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

    private fun sendRequest(key: String) = webSocket.sendJson(SendRequestMessage(key))
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