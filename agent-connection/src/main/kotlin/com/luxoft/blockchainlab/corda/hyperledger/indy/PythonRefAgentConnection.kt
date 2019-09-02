package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import mu.KotlinLogging
import net.iharder.Base64
import rx.*
import rx.Observable
import rx.schedulers.Schedulers
import java.net.URI
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Implements Indy Agent Connection transport ([AgentConnection]).
 * Whenever the socket is closed on the remote side, the connection is scheduled to re-establish.
 * A dual-mode reconnection procedure ([doReconnect]) is called asynchronously with increasing delay between
 * attempts, when the underlying WebSocket reports it's been closed from remote. It is also called asynchronously
 * when the caller requests to listen for a certain message. When the caller requests to transmit a message,
 * the calling thread blocks for [operationTimeoutMs] or until the connection is re-established, whichever happens first.
 * The timeout is set in [connect] method (default value is 60000ms, i.e. 1 minute).
 * [TimeoutException] is thrown when no response is received within this timeframe.
 * The transport is therefore capable to survive network outages that happen for example when the device loses the network
 * coverage. It should be kept in mind that when the Agent (PythonRefAgent) has queued certain quantity of messages to be
 * sent to the client, and the connection was broken, the agent will route the queued messages to the first incoming
 * client, so that if another client connects to the Agent within the time of outage, it will consume messages pertaining
 * to the client that has been recently disconnected. This inevitably leads to loss of data. Avoid the situation when
 * multiple clients concurrently connect to a single Agent.
 */
class PythonRefAgentConnection : AgentConnection {
    private val log = KotlinLogging.logger {}

    private lateinit var url: String
    private lateinit var login: String
    private lateinit var password: String
    private var operationTimeoutMs: Long = 60000
    private val isReconnecting = AtomicBoolean(false)
    private val stopReconnecting = AtomicBoolean(false)
    private lateinit var onCloseSubscription: Subscription
    private lateinit var onReconnectSubscription: Subscription

    /**
     * Private method sends the CONNECT message with appropriate login/password if the client is not logged in.
     * Otherwise returns success (notifies the [observer]).
     * @param observer an object implementing [Subscriber]<Unit> that is notified with the handshake result.
     */
    private fun doHandshake(observer: Subscriber<in Unit>) {
        /**
         * Check the agent's current state.
         * The agent will respond with the "state" message
         */
        var unsubscribe: ()->Unit = {}
        var sub2: Subscription? = null
        val subscription = webSocket.receiveMessageOfType<State>(MESSAGE_TYPES.STATE_RESPONSE)
                .timeout(operationTimeoutMs, TimeUnit.MILLISECONDS)
                .subscribe({ stateResponse ->
            try {
                if (!checkUserLoggedIn(stateResponse, login)) {

                    sub2 = webSocket.receiveMessageOfType<State>(MESSAGE_TYPES.STATE_RESPONSE)
                            .timeout(operationTimeoutMs, TimeUnit.MILLISECONDS)
                            .subscribe({ newState ->
                        try {
                            if (!checkUserLoggedIn(newState, login)) {
                                log.error { "Unable to connect to Wallet $login" }
                                throw AgentConnectionException("Error connecting to $url as $login")
                            } else {
                                observer.onNext(Unit)
                            }
                        } catch (e: Throwable) {
                            observer.onError((e))
                        }
                    }, { e: Throwable -> observer.onError((e)); unsubscribe() })

                    /**
                     * If the agent is logged in by different user, send the "connect" request
                     * to take over the connection
                     */
                    sendAsJson(WalletConnect(login, password))

                } else {
                    observer.onNext(Unit)
                }
            } catch (e: Throwable) {
                observer.onError((e))
            }
        }, { e: Throwable -> observer.onError(e); unsubscribe() })

        unsubscribe = { subscription.unsubscribe(); sub2?.unsubscribe() }

        /**
         * Send the state request
         */
        sendAsJson(StateRequest())
    }

    /**
     * Dual-mode reconnection procedure
     */
    private fun doReconnect(blockingMode: Boolean) : Boolean {
        var reconnected = AtomicBoolean(false)
        try {
            if (isReconnecting.compareAndSet(false, true)) {
                /**
                 * Non-blocking code
                 */
                var retryTimeoutMs: Long = 3000
                val connectionAttempt = Observable.create<Unit> { observer ->
                    log.error { "Will attempt to reconnect $login to $url in ${retryTimeoutMs}ms" }
                    Thread.sleep(retryTimeoutMs)
                    if (!stopReconnecting.compareAndSet(true, true)) {
                        Observable.create<Unit> { connectionObserver ->
                            try {
                                if (!observer.isUnsubscribed) {
                                    if (!webSocket.reconnectBlocking())
                                        throw AgentConnectionException(webSocket.reason ?: "Error connecting to $url")
                                    else
                                        doHandshake(connectionObserver)
                                }
                            } catch (e: Throwable) {
                                connectionObserver.onError(e)
                            }
                        }.subscribeOn(Schedulers.computation()).apply {
                            timeout(operationTimeoutMs, TimeUnit.MILLISECONDS).subscribe({
                                pollAgentWorker = createAgentWorker()
                                observer.onNext(it)
                                observer.unsubscribe()
                            }, {
                                if (!observer.isUnsubscribed) {
                                    observer.onError(it)
                                    observer.unsubscribe()
                                }
                            })
                        }
                    } else observer.onError(AgentConnectionException("Reconnection process stopped"))
                }.subscribeOn(Schedulers.computation())
                val onSuccess: (Unit)->Unit = {
                    reconnected.set(true)
                    log.info { "Reconnected to $url with login $login" }
                    isReconnecting.set(false)
                }
                var onError: ((Throwable)->Unit)? = null
                onError = {
                    if (!stopReconnecting.compareAndSet(true, false)) {
                        if (retryTimeoutMs * 2 < this.operationTimeoutMs) retryTimeoutMs *= 2
                        log.error { "Connection attempt for $login failed with $it, increasing the reconnection timeout (${retryTimeoutMs}ms)" }
                        connectionAttempt.subscribe({ onSuccess(Unit) }, { onError!!(it) })
                    } else isReconnecting.set(false)
                }
                /**
                 * subscribe() must NOT be done on the calling thread
                 */
                connectionAttempt.subscribe({ onSuccess(Unit) }, { onError!!(it)})
            }
            if (blockingMode) {
                Observable.create<Boolean> {
                    /**
                     * Wait until connection is established or TimeoutException is thrown
                     */
                    while (isReconnecting.get()) { Thread.sleep(100) }
                    reconnected.compareAndSet(false, webSocket.isOpen)
                    it.onNext(reconnected.get())
                }.subscribeOn(Schedulers.computation()).apply {
                    timeout(operationTimeoutMs, TimeUnit.MILLISECONDS).subscribe({
                        if (!it) log.error { "All reconnection attempts for login $login failed" }
                    }, {
                        log.error { "Reconnection in blocking mode for login $login failed with exception: $it" }
                        stopReconnecting.compareAndSet(false, true)
                    })
                }.toBlocking().first()
            }
        } catch (e: Throwable) {
            log.error { "Unable to reconnect while trying to send/receive data, due to $e at ${e.stackTrace}" }
        }
        return reconnected.get()
    }

    private fun clear() {
        pollAgentWorker?.interrupt() ?: log.warn { "Agent status is connected while pollAgentWorker is null" }
        toProcessPairwiseConnections.clear()
        awaitingPairwiseConnections.clear()
        indyParties.clear()
    }

    override fun disconnect() {
        /**
         * reset event handlers
         */
        onCloseSubscription.unsubscribe()
        onReconnectSubscription.unsubscribe()

        if (getConnectionStatus() == AgentConnectionStatus.AGENT_CONNECTED) {
            webSocket.closeBlocking()
        }
        if (isReconnecting.get()) {
            /**
             * break reconnection loop
             */
            stopReconnecting.compareAndSet(false, true)
        }
        clear()
    }

    /**
     * Connects to an agent's endpoint
     */
    override fun connect(url: String, login: String, password: String, timeoutMs: Long): Single<Unit> {

        this.url = url
        this.login = login
        this.password = password
        this.operationTimeoutMs = timeoutMs
        return Single.create { resultObserver ->
            Observable.create<Unit> { connectionObserver ->
                try {
                    webSocket = AgentWebSocketClient(URI(url), login)
                    webSocket.apply {
                        onCloseSubscription = onSocketCloseSubscription().subscribe { isRemote ->
                            connectionStatus = AgentConnectionStatus.AGENT_DISCONNECTED
                            doReconnect(false) // reconnect in non-blocking mode
                        }
                        onReconnectSubscription = onClosedSocketOperation().subscribe { isBlocking ->
                            doReconnect(isBlocking)
                        }
                        if(!connectBlocking())
                            throw AgentConnectionException(webSocket.reason ?: "Error connecting to $url")
                        else
                            doHandshake(connectionObserver)
                    }
                } catch (e: Throwable) {
                    connectionObserver.onError(e)
                }
            }.subscribeOn(Schedulers.computation()).apply {
                timeout(operationTimeoutMs, TimeUnit.MILLISECONDS).subscribe({
                    pollAgentWorker = createAgentWorker()
                    resultObserver.onSuccess(it)
                    resultObserver.unsubscribe()
                }, {
                    if (!resultObserver.isUnsubscribed)
                        resultObserver.onError(it)
                })
            }
        }
    }

    private var connectionStatus: AgentConnectionStatus = AgentConnectionStatus.AGENT_DISCONNECTED

    override fun getConnectionStatus(): AgentConnectionStatus {
        return when {
            connectionStatus == AgentConnectionStatus.AGENT_DISCONNECTED -> AgentConnectionStatus.AGENT_DISCONNECTED
            webSocket.isOpen -> AgentConnectionStatus.AGENT_CONNECTED
            else -> {
                connectionStatus = AgentConnectionStatus.AGENT_DISCONNECTED
                connectionStatus
            }
        }
    }

    private lateinit var webSocket : AgentWebSocketClient

    private val indyParties = ConcurrentHashMap<String, IndyPartyConnection>()

    private fun getPubkeyFromInvite(invite: String): String {
        val invEncoded = invite.split("?c_i=")[1]
        val inv = Base64.decode(invEncoded).toString(Charsets.UTF_8)
        val keyList = SerializationUtils.jSONToAny<Map<String, List<String>>>(inv)["recipientKeys"]
        return keyList?.get(0)!!
    }

    /**
     * Returns true, based on the "state" message returned from the agent, if the agent is already logged-in.
     */
    private fun checkUserLoggedIn(stateMessage: State?, userName: String): Boolean {
        return if(stateMessage != null &&
                stateMessage.content?.get("initialized") == true &&
                stateMessage.content["agent_name"] == userName && webSocket.isOpen) {
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
        val inviteAccepted : Single<IndyPartyConnection> = Single.create { observer ->
            try {
                if (getConnectionStatus() == AgentConnectionStatus.AGENT_CONNECTED) {
                    /**
                     * To accept the invite, first inform the agent with "receive_invite" message
                     * this is done after subscribing to "invite_received".
                     */
                    val pubKey = getPubkeyFromInvite(invite)
                    /**
                     * The agent must respond with "invite_received" message, containing the public key from invite
                     */
                    var unsubscribe: ()->Unit = {}
                    var sub2: Subscription? = null
                    var sub3: Subscription? = null
                    val subscription = webSocket.receiveMessageOfType<InviteReceivedMessage>(MESSAGE_TYPES.INVITE_RECEIVED, pubKey)
                            .timeout(operationTimeoutMs, TimeUnit.MILLISECONDS)
                            .subscribe({ invRcv ->
                        /**
                         * Now instruct the agent to send the connection request with "send_request" message.
                         * The agent builds up the connection request and forwards it to the other IndyParty's endpoint,
                         * recalling the invite by its public key. The request is sent after the subscription to "response_received".
                         * The agent receives the response from the other party and informs the client with "response_received"
                         * message
                         */
                        sub2 = webSocket.receiveMessageOfType<ObjectNode>(MESSAGE_TYPES.RESPONSE_RECEIVED, pubKey)
                                .timeout(operationTimeoutMs, TimeUnit.MILLISECONDS)
                                .subscribe({
                            /**
                             * Wait until connection_key appears in the STATE
                             */
                            sub3 = waitForPairwiseConnection(pubKey).timeout(operationTimeoutMs, TimeUnit.MILLISECONDS).subscribe({ pairwise ->
                                val theirDid = pairwise["their_did"].asText()
                                val indyParty = IndyParty(webSocket, theirDid,
                                        pairwise["metadata"]["their_endpoint"].asText(),
                                        pairwise["metadata"]["connection_key"].asText(),
                                        pairwise["my_did"].asText())
                                indyParties[theirDid] = indyParty
                                observer.onSuccess(indyParty)
                            }, { e ->
                                unsubscribe()
                                if (e is TimeoutException) {
                                    awaitingPairwiseConnections.remove(pubKey)
                                    observer.onError(AgentConnectionException("Inviting party delayed to report to the Agent. Try increasing the timeout."))
                                } else observer.onError(e)
                            })
                        }, { e: Throwable -> unsubscribe(); observer.onError(e) })

                        /**
                         * Now send the request
                         */
                        sendRequest(pubKey)

                    }, { e: Throwable -> unsubscribe(); observer.onError(e) })
                    unsubscribe = { subscription.unsubscribe(); sub2?.unsubscribe(); sub3?.unsubscribe() }

                    /**
                     * Now send "receive_invite"
                     */
                    sendAsJson(ReceiveInviteMessage(invite))

                } else {
                    throw AgentConnectionException("AgentConnection object has wrong state")
                }
            } catch (e: Throwable) {
                observer.onError(e)
            }
        }
        return inviteAccepted.timeout(operationTimeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun sendAsJson(obj: Any) = webSocket.sendAsJson(obj)

    /**
     * Retrieves a new invite from the agent
     */
    override fun generateInvite(): Single<String> {
        val inviteGenerated : Single<String> = Single.create { observer ->
            try {
                /**
                 * to generate the invite, send "generate_invite" message to the agent, and wait for "invite_generated"
                 * which must contain an invite
                 */
                var unsubscribe: ()->Unit = {}
                val subscription = webSocket.receiveMessageOfType<ReceiveInviteMessage>(MESSAGE_TYPES.INVITE_GENERATED)
                        .timeout(operationTimeoutMs, TimeUnit.MILLISECONDS)
                        .subscribe({ msg ->
                    observer.onSuccess(msg.invite)
                }, { e: Throwable -> unsubscribe(); observer.onError(e) })
                unsubscribe = { subscription.unsubscribe() }
                /**
                 * After subscription, send the "generate_invite" request
                 */
                webSocket.sendAsJson(SendMessage(type = MESSAGE_TYPES.GENERATE_INVITE))

            } catch (e: Throwable) {
                observer.onError(e)
            }
        }
        return inviteGenerated.timeout(operationTimeoutMs, TimeUnit.MILLISECONDS)
    }

    private val toProcessPairwiseConnections = LinkedBlockingQueue<Pair<String, SingleSubscriber<in JsonNode>>>()
    private val awaitingPairwiseConnections = ConcurrentHashMap<String, SingleSubscriber<in JsonNode>>()
    /**
     * Agent's state polling thread. It resumes whenever [toProcessPairwiseConnections] is non-empty.
     * It also loops by itself when
     */
    private var pollAgentWorker: Thread? = null

    private fun createAgentWorker() = thread {
        fun processStateResponse(stateResponse: ObjectNode) {
            stateResponse["content"]["pairwise_connections"].forEach { pairwise ->
                try {
                    val publicKey = pairwise["metadata"]["connection_key"].asText()
                    awaitingPairwiseConnections.remove(publicKey)?.onSuccess(pairwise)

                } catch (e: Throwable) {
                    log.warn(e) { "invalid pairwise connection (no connection key) found in the state" }
                }
            }
        }

        try {
            while (!Thread.interrupted()) {
                /**
                 * Sleep until a pairwise connection observer is registered.
                 */
                if (awaitingPairwiseConnections.isEmpty())
                    toProcessPairwiseConnections.take().apply {
                        awaitingPairwiseConnections[first] = second
                    }

                do {
                    val pairwiseConnection = toProcessPairwiseConnections.poll()?.apply {
                        awaitingPairwiseConnections[first] = second
                    }
                } while (pairwiseConnection != null)

                if (awaitingPairwiseConnections.isEmpty()) continue
                /**
                 * Subscribe for the response
                 */
                val single = webSocket.receiveMessageOfType<ObjectNode>(MESSAGE_TYPES.STATE_RESPONSE).toBlocking().toFuture()
                /**
                 * After subscription send the state request
                 */
                sendAsJson(StateRequest())
                processStateResponse(single.get())
                /**
                 * Sleep to reduce polling the (local) agent
                 */
                Thread.sleep(500)
            }
        } catch (e: Throwable) {
            if (e is java.lang.InterruptedException) log.info { "${Thread.currentThread()} interrupted by disconnect()" }
            else throw e
        }
    }

    private fun waitForPairwiseConnection(pubKey: String): Single<JsonNode> {
        return Single.create { observer ->
            try {
                /**
                 * Put the observer in the queue and notify the worker to poll the agent state.
                 * When the worker finds the public key in the parsed state, it will notify the observer.
                 */
                toProcessPairwiseConnections.put(pubKey to observer)
            } catch (e: Throwable) {
                observer.onError(e)
            }
        }
    }
    private fun subscribeOnRequestReceived() {
        /**
         * On an incoming connection request, the agent must send the "request_received"
         */
        var unsubscribe: ()->Unit = {}
        var sub2: Subscription? = null
        val subscription = webSocket.receiveMessageOfType<RequestReceivedMessage>(MESSAGE_TYPES.REQUEST_RECEIVED)
                .timeout(operationTimeoutMs, TimeUnit.MILLISECONDS)
                .subscribe ({
            /**
             * On the "request_received" message, reply with "send_response" with remote DID to any incoming connection request.
             * At this stage it doesn't matter which party sent the connection request, because it's not possible to correlate
             * "request_received" message and the invite. For the purpose of PoC we reply with "send_response"
             * on each "request_received", no matter which party is on the other side.
             *
             * TODO: suggest an improvement in pythonic indy-agent that incorporates the invite's public key in the "request" message,
             * TODO: so that it's possible to correlate "request_received" which includes "request" and the invite
             */
            sub2 = webSocket.receiveMessageOfType<RequestResponseSentMessage>(MESSAGE_TYPES.RESPONSE_SENT, it.did)
                    .timeout(operationTimeoutMs, TimeUnit.MILLISECONDS)
                    .subscribe ({
                log.info { "Accepted connection request from ${it.did}" }
            }, { unsubscribe() })

            sendAsJson(RequestSendResponseMessage(it.did))

        }, { unsubscribe() })
        unsubscribe = { subscription.unsubscribe(); sub2?.unsubscribe() }
    }
    /**
     * Waits for incoming connection from whatever party which possesses the invite
     */
    override fun waitForInvitedParty(invite: String, timeoutMs: Long): Single<IndyPartyConnection> {
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
                    var unsubscribe: ()->Unit = {}
                    val subscription = waitForPairwiseConnection(pubKey).timeout(timeoutMs, TimeUnit.MILLISECONDS).subscribe({ pairwise ->
                        val theirDid = pairwise["their_did"].asText()
                        val indyParty = IndyParty(webSocket, theirDid,
                                pairwise["metadata"]["their_endpoint"].asText(),
                                pairwise["metadata"]["connection_key"].asText(),
                                pairwise["my_did"].asText())
                        indyParties[theirDid] = indyParty
                        observer.onSuccess(indyParty)
                    }, { e ->
                        unsubscribe()
                        if (e is TimeoutException) {
                            awaitingPairwiseConnections.remove(pubKey)
                            observer.onError(AgentConnectionException("Invited party delayed to report to the Agent. Try increasing the timeout."))
                        } else observer.onError(e)
                    })
                    unsubscribe = { subscription.unsubscribe() }
                } else {
                    throw AgentConnectionException("Agent is disconnected")
                }
            } catch (e: Throwable) {
                observer.onError(e)
            }
        }
    }

    override fun getIndyPartyConnection(partyDID: String): Single<IndyPartyConnection?> {
        val indyPartyConnection: Single<IndyPartyConnection?> = Single.create { observer ->
            try {
                if (getConnectionStatus() == AgentConnectionStatus.AGENT_CONNECTED) {
                    /**
                     * Query local connection objects associated with the remote party's DID
                     */
                    if (partyDID in indyParties.keys) observer.onSuccess(indyParties[partyDID]) else {
                        /**
                         * If not found, query agent state for the properties of the previously set pairwise connection
                         */
                        var unsubscribe: ()->Unit = {}
                        val subscription = webSocket.receiveMessageOfType<ObjectNode>(MESSAGE_TYPES.STATE_RESPONSE)
                                .timeout(operationTimeoutMs, TimeUnit.MILLISECONDS)
                                .subscribe ({ stateResponse ->
                            val pairwise = stateResponse["content"]["pairwise_connections"].find { node ->
                                node["their_did"].asText() == partyDID
                            }
                            if (pairwise != null) {
                                observer.onSuccess(IndyParty(webSocket, partyDID,
                                        pairwise["metadata"]["their_endpoint"].asText(),
                                        pairwise["metadata"]["connection_key"].asText(),
                                        pairwise["my_did"].asText()))
                            } else {
                                observer.onSuccess(null)
                                log.info {
                                    "Remote IndyParty (DID:$partyDID) is unknown to the agent. " +
                                            "Initiate a new connection using generateInvite()/acceptInvite()/waitForInvitedParty()"
                                }
                            }
                        }, { unsubscribe(); observer.onError(it) })
                        unsubscribe = { subscription.unsubscribe() }
                        sendAsJson(StateRequest())
                    }
                } else {
                    throw AgentConnectionException("Agent is disconnected")
                }
            } catch (e: Throwable) {
                observer.onError(e)
            }
        }
        return indyPartyConnection.timeout(operationTimeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun sendRequest(key: String) = webSocket.sendAsJson(SendRequestMessage(key))
}

object MESSAGE_TYPES {
    val CONN_BASE = "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/"
    val ADMIN_CONNECTIONS_BASE = "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/admin_connections/1.0/"
    val ADMIN_BASE = "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/admin/1.0/"
    val ADMIN_WALLETCONNECTION_BASE = "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/admin_walletconnection/1.0/"
    val ADMIN_BASICMESSAGE_BASE = "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/admin_basicmessage/1.0/"
    val RESPONSE = CONN_BASE + "response"
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
data class InviteReceivedMessage(val connection_key: String, val label: String, val endpoint: String, @JsonProperty("@type") val type: String)
data class SendRequestMessage(val connection_key: String, @JsonProperty("@type") val type: String = MESSAGE_TYPES.SEND_REQUEST)
data class RequestReceivedMessage(val label: String, val did: String, val endpoint: String, @JsonProperty("@type") val type: String)
data class RequestSendResponseMessage(val did: String, @JsonProperty("@type") val type: String = MESSAGE_TYPES.SEND_RESPONSE)
data class RequestResponseReceivedMessage(val their_did: String, val history: ObjectNode, @JsonProperty("@type") val type: String)
data class RequestResponseSentMessage(@JsonProperty("@type") val type: String = MESSAGE_TYPES.RESPONSE_SENT, val label: String, val did: String)
data class SendMessage(val to: String? = null, val message: TypedBodyMessage? = null, @JsonProperty("@type") val type: String = MESSAGE_TYPES.SEND_MESSAGE, val from: String? = null)
data class MessageReceivedMessage(val from: String, val sent_time: String, val content: TypedBodyMessage)
data class MessageReceived(val id: String?, val with: String?, val message: MessageReceivedMessage, @JsonProperty("@type") val type: String = MESSAGE_TYPES.SEND_MESSAGE)
data class LoadMessage(val with: String, @JsonProperty("@type") val type: String = MESSAGE_TYPES.GET_MESSAGES)
data class TypedBodyMessage(val message: Any, @JsonProperty("@class") val clazz: String, val correlationId: String = UUID.randomUUID().toString())