package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.models.*
import rx.Single
import rx.Subscription


enum class AgentConnectionStatus { AGENT_CONNECTED, AGENT_DISCONNECTED }

/**
 * Represents a connected Indy Party
 */
interface IndyPartyConnection {

    /**
     * Sends a credential offer message to the Indy Party represented by this connection
     *
     * @param offer credential offer message
     */
    fun sendCredentialOffer(offer: CredentialOffer)

    /**
     * Receives a credential offer from the Indy Party represented by this connection
     *
     * @return observable ([Single]<>) object emitting a [CredentialOffer] upon subscription
     */
    fun receiveCredentialOffer(): Single<CredentialOffer>

    /**
     * Sends a credential request message to the Indy Party represented by this connection
     *
     * @param request credential request message (JSON-serialized [CredentialRequestInfo])
     */
    fun sendCredentialRequest(request: CredentialRequestInfo)

    /**
     * Receives a credential request from the Indy Party represented by this connection
     *
     * @return observable ([Single]<>) object emitting a [CredentialRequestInfo] upon subscription
     */
    fun receiveCredentialRequest(): Single<CredentialRequestInfo>

    /**
     * Sends a credential to Indy Party represented by this connection
     *
     * @param credential credential message
     */
    fun sendCredential(credential: CredentialInfo)

    /**
     * Receives a credential from the Indy Party represented by this connection
     *
     * @return observable ([Single]<>) object emitting a [CredentialInfo] upon subscription
     */
    fun receiveCredential(): Single<CredentialInfo>

    /**
     * Sends a proof request message to the Indy Party represented by this connection
     *
     * @param request proof request message
     */
    fun sendProofRequest(request: ProofRequest)

    /**
     * Receives a proof request from the Indy Party represented by this connection
     *
     * @return observable ([Single]<>) object emitting a single [ProofRequest] upon subscription
     */
    fun receiveProofRequest(): Single<ProofRequest>

    /**
     * Sends a proof to the Indy Party represented by this connection
     *
     * @param proof proof message
     */
    fun sendProof(proof: ProofInfo)

    /**
     * Receives a proof from the Indy Party represented by this connection
     *
     * @return observable ([Single]<>) object emitting a single [ProofInfo] upon subscription
     */
    fun receiveProof(): Single<ProofInfo>

    /**
     * Returns the connected Indy Party session DID
     */
    fun partyDID(): String

    /**
     * Returns self session DID
     */
    fun myDID(): String

    /**
     * Returns observable ([Single]<>) object, emitting string-encocoded Tails file by the given tails hash.
     *
     * @param tailsHash string-encocoded Tails file
     *
     * @return observable ([Single]<>) object emitting [TailsResponse] object
     */
    fun requestTails(tailsHash: String) : Single<TailsResponse>

    /**
     * Sets handler for client's tails file requests
     *
     * @param handler a function producing [TailsResponse] from [TailsRequest]
     */
    fun handleTailsRequestsWith(handler: (TailsRequest) -> TailsResponse)
}

class AgentConnectionException(obj: Any) :
        RuntimeException("AgentConnection exception: $obj")

/**
 * Convenience extension to subscribe with a single lambda on both onSuccess() and onError()
 */
fun <T> Single<T>.handle(handle: (message: T?, ex: Throwable?) -> Unit): Subscription = this.subscribe({ handle(it, null) }, { handle(null, it) })

/**
 * Represents a connection to Indy Agent
 */
interface AgentConnection {

    /**
     * Connects to Indy Agent's connection endpoint
     *
     * @param url Indy Agent's endpoint URL
     * @param login endpoint's login
     * @param password endpoints's password
     * @param timeoutMs connection timeout in milliseconds (30000 by default)
     * @return [Single]<Unit> emitting a Unit upon a successful handshake
     */
    fun connect(url: String, login: String, password: String, timeoutMs:Long = 60000): Single<Unit>

    /**
     * Disconnects from the Agent's endpoint
     */
    fun disconnect()

    /**
     * Requests the Agent to generate an Invite link.
     *
     * @return observable ([Single]<>) emitting the generated invite
     */
    fun generateInvite(): Single<String>

    /**
     * Establishes a connection to remote Indy Party based on the given invite
     *
     * @return observable ([Single]<>) emitting an [IndyPartyConnection]
     */
    fun acceptInvite(invite: String): Single<IndyPartyConnection>

    /**
     * Wait for incoming connection from remote Indy Party that accepted the specific invite
     *
     * @param invite invite string
     * @param timeout amount of milliseconds to wait for party
     *
     * @return observable ([Single]<>) emitting an [IndyPartyConnection]
     */
    fun waitForInvitedParty(invite: String, timeout:Long = 60000): Single<IndyPartyConnection>

    /**
     * Recalls previously connected Indy Party by its DID and reconstructs corresponding {@code IndyPartyConnection}
     *
     * @param partyDID invite string
     *
     * @return observable ([Single]<>) emitting an [IndyPartyConnection]
     */
    fun getIndyPartyConnection(partyDID: String): Single<IndyPartyConnection?>

    /**
     * Returns Agent connection status
     *
     * @return current Agent connection status represented by [AgentConnectionStatus]
     */
    fun getConnectionStatus(): AgentConnectionStatus

}

