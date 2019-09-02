package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.models.*
import mu.KotlinLogging
import rx.Single
import java.util.concurrent.atomic.AtomicReference

/**
 * Represents remote Indy Party
 */
class IndyParty(private val webSocket: AgentWebSocketClient, val did: String, val endpoint: String, val verkey: String, val myDid : String) : IndyPartyConnection {

    private val log = KotlinLogging.logger {}

    /**
     * Returns the connected Indy Party session DID
     */
    override fun partyDID(): String = did

    /**
     * Returns self session DID
     */
    override fun myDID(): String = myDid

    /**
     * Sends a credential offer message to the Indy Party
     *
     * @param offer credential offer message
     */
    override fun sendCredentialOffer(offer: CredentialOffer) = webSocket.sendClassObject(offer, this)

    /**
     * Receives a credential offer from the Indy Party
     *
     * @return observable (Single<>) object emitting a single [CredentialOffer] upon subscription
     */
    override fun receiveCredentialOffer() = webSocket.receiveClassObject<CredentialOffer>(this)

    /**
     * Sends a credential request message to the Indy Party
     *
     * @param request credential request message
     */
    override fun sendCredentialRequest(request: CredentialRequestInfo) = webSocket.sendClassObject(request, this)

    /**
     * Receives a credential request from the Indy Party
     *
     * @return observable (Single<>) object emitting a [CredentialRequestInfo] upon subscription
     */
    override fun receiveCredentialRequest() = webSocket.receiveClassObject<CredentialRequestInfo>(this)

    /**
     * Sends a credential to Indy Party
     *
     * @param credential credential message
     */
    override fun sendCredential(credential: CredentialInfo) = webSocket.sendClassObject(credential, this)

    /**
     * Receives a credential from the Indy Party
     *
     * @return observable (Single<>) object emitting a single [CredentialRequestInfo] upon subscription
     */
    override fun receiveCredential() = webSocket.receiveClassObject<CredentialInfo>(this)

    /**
     * Sends a proof request message to the Indy Party
     *
     * @param request proof request message
     */
    override fun sendProofRequest(request: ProofRequest) = webSocket.sendClassObject(request, this)

    /**
     * Receives a proof request from the Indy Party
     *
     * @return observable (Single<>) object emitting a single [ProofRequest] upon subscription
     */
    override fun receiveProofRequest() = webSocket.receiveClassObject<ProofRequest>(this)

    /**
     * Sends a proof to the Indy Party
     *
     * @param proof proof message
     */
    override fun sendProof(proof: ProofInfo) = webSocket.sendClassObject(proof, this)

    /**
     * Receives a proof from the Indy Party
     *
     * @return observable (Single<>) object emitting a single [ProofInfo] upon subscription
     */
    override fun receiveProof() = webSocket.receiveClassObject<ProofInfo>(this)

    /**
     * Returns observable ([Single]<>) object, emitting JSON-encocoded Tails file by the given tails hash.
     *
     * @param tailsHash string-encocoded Tails file
     *
     * @return observable ([Single]<>) object emitting [TailsResponse] object
     */
    override fun requestTails(tailsHash: String) : Single<TailsResponse> {
        val result : Single<TailsResponse> = webSocket.receiveClassObject(this)
        webSocket.sendClassObject(TailsRequest(tailsHash), this)
        return result
    }

    private val requestHandlerRef: AtomicReference<(TailsRequest)->TailsResponse> =
        AtomicReference<(TailsRequest)->TailsResponse> { TailsResponse(it.tailsHash, mapOf())}

    private lateinit var tailRequestMessageHandler: (TailsRequest) -> Unit

    init {
        tailRequestMessageHandler = {
            try {
                val tailsResponse = requestHandlerRef.get().invoke(it)
                webSocket.sendClassObject(tailsResponse, this)
            } catch (e: Throwable) {
                log.error(e) { "Error processing tails request" }
            } finally {
                webSocket.receiveClassObject<TailsRequest>(this).subscribe(tailRequestMessageHandler, {})
            }
        }
    }

    /**
     * Sets handler for client's tails file requests
     *
     * @param handler a function producing [TailsResponse] from [TailsRequest]
     */
    override fun handleTailsRequestsWith(handler: (TailsRequest) -> TailsResponse) {
        webSocket.receiveClassObject<TailsRequest>(this).subscribe(tailRequestMessageHandler, {})
        requestHandlerRef.set(handler)
    }
}

