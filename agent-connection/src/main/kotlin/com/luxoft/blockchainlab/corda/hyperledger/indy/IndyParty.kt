package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.models.*

/**
 * Represents remote Indy Party
 */
class IndyParty(private val webSocket: AgentWebSocketClient, val did: String, val endpoint: String, val verkey: String, val myDid : String) : IndyPartyConnection {

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
     * @param offer
     *          {@code offer} - credential offer message
     */
    override fun sendCredentialOffer(offer: CredentialOffer) = webSocket.sendClassObject(offer, this)

    /**
     * Receives a credential offer from the Indy Party
     *
     * @return
     *          observable (Single<>) object emitting a single CredentialOffer upon subscription
     */
    override fun receiveCredentialOffer() = webSocket.receiveClassObject<CredentialOffer>(this)

    /**
     * Sends a credential request message to the Indy Party
     *
     * @param request
     *          {@code request} - credential request message
     */
    override fun sendCredentialRequest(request: CredentialRequestInfo) = webSocket.sendClassObject(request, this)

    /**
     * Receives a credential request from the Indy Party
     *
     * @return
     *          observable (Single<>) object emitting a CredentialRequestInfo upon subscription
     */
    override fun receiveCredentialRequest() = webSocket.receiveClassObject<CredentialRequestInfo>(this)

    /**
     * Sends a credential to Indy Party
     *
     * @param credential
     *          {@code credential} - credential message
     */
    override fun sendCredential(credential: CredentialInfo) = webSocket.sendClassObject(credential, this)

    /**
     * Receives a credential from the Indy Party
     *
     * @return
     *          observable (Single<>) object emitting a single CredentialRequestInfo upon subscription
     */
    override fun receiveCredential() = webSocket.receiveClassObject<CredentialInfo>(this)

    /**
     * Sends a proof request message to the Indy Party
     *
     * @param request
     *          {@code request} - proof request message
     */
    override fun sendProofRequest(request: ProofRequest) = webSocket.sendClassObject(request, this)

    /**
     * Receives a proof request from the Indy Party
     *
     * @return
     *          observable (Single<>) object emitting a single ProofRequest upon subscription
     */
    override fun receiveProofRequest() = webSocket.receiveClassObject<ProofRequest>(this)

    /**
     * Sends a proof to the Indy Party
     *
     * @param proof
     *          {@code proof} - proof message
     */
    override fun sendProof(proof: ProofInfo) = webSocket.sendClassObject(proof, this)

    /**
     * Receives a proof from the Indy Party
     *
     * @return
     *          observable (Single<>) object emitting a single ProofInfo upon subscription
     */
    override fun receiveProof() = webSocket.receiveClassObject<ProofInfo>(this)
}

