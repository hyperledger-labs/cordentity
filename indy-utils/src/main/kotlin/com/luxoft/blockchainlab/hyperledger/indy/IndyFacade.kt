package com.luxoft.blockchainlab.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.ledger.LedgerService
import com.luxoft.blockchainlab.hyperledger.indy.models.*
import com.luxoft.blockchainlab.hyperledger.indy.wallet.WalletService


interface IndyFacade {
    val walletService: WalletService
    val ledgerService: LedgerService

    /**
     * Creates [Schema] using [WalletService] and stores it using [LedgerService]
     *
     * @param name: [String] - schema name
     * @param version: [String] - schema version in format "d.d.d"
     * @param attributes: [List] of [String] - list of schema's attribute names
     *
     * @return [Schema]
     */
    fun createSchemaAndStoreOnLedger(name: String, version: String, attributes: List<String>): Schema

    /**
     * Creates [CredentialDefinition] using [WalletService] and stores it using [LedgerService]
     *
     * @param schemaId: [SchemaId] - id of schema paired with this credential definition
     * @param enableRevocation: [Boolean] - flag if you need revocation be enabled
     *
     * @return [CredentialDefinition]
     */
    fun createCredentialDefinitionAndStoreOnLedger(schemaId: SchemaId, enableRevocation: Boolean): CredentialDefinition

    /**
     * Creates [RevocationRegistryDefinition] and first [RevocationRegistryEntry] using [WalletService] and stores it
     * using [LedgerService]
     *
     * @param credentialDefinitionId: [CredentialDefinitionId] - id of credential definition paired with this revocation
     *  registry
     * @param maxCredentialNumber: [Int] - maximum credential count that this revocation registry can hold
     *
     * @return [RevocationRegistryInfo] - combination of [RevocationRegistryDefinition] and [RevocationRegistryEntry]
     */
    fun createRevocationRegistryAndStoreOnLedger(
        credentialDefinitionId: CredentialDefinitionId,
        maxCredentialNumber: Int
    ): RevocationRegistryInfo

    /**
     * Creates [CredentialOffer] using [WalletService]
     *
     * @param credentialDefinitionId: [CredentialDefinitionId]
     *
     * @return [CredentialOffer]
     */
    fun createCredentialOffer(credentialDefinitionId: CredentialDefinitionId): CredentialOffer

    /**
     * Creates [CredentialRequest] using [WalletService]
     *
     * @param proverDid: [String]
     * @param offer: [CredentialOffer]
     *
     * @return [CredentialRequestInfo] - [CredentialRequest] and all related data
     */
    fun createCredentialRequest(
        proverDid: String,
        offer: CredentialOffer,
        masterSecretId: String = "main"
    ): CredentialRequestInfo

    /**
     * Issues [Credential] by [CredentialRequest] and [CredentialOffer] using [WalletService].
     * If revocation is enabled it will hold one of [maxCredentialNumber].
     *
     * @param credentialRequest: [CredentialRequestInfo] - [CredentialRequest] and all reliable info
     * @param offer: [CredentialOffer] - credential offer
     * @param revocationRegistryId: [RevocationRegistryDefinitionId]
     * @param proposalProvider: lambda returning [Map] of [String] to [CredentialValue] - credential proposal
     *
     * @return [CredentialInfo] - credential and all reliable data
     */
    fun issueCredentialAndUpdateLedger(
        credentialRequest: CredentialRequestInfo,
        offer: CredentialOffer,
        revocationRegistryId: RevocationRegistryDefinitionId?,
        proposalProvider: () -> Map<String, CredentialValue>
    ): CredentialInfo

    /**
     * Stores [Credential] in prover's wallet gathering data using [LedgerService]
     *
     * @param credentialInfo: [CredentialInfo] - credential and all reliable data
     * @param credentialRequest: [CredentialRequestInfo] - credential request and all reliable data
     * @param offer: [CredentialOffer]
     */
    fun receiveCredential(
        credentialInfo: CredentialInfo,
        credentialRequest: CredentialRequestInfo,
        offer: CredentialOffer
    )

    /**
     * Revokes previously issued [Credential] using [WalletService] and [LedgerService]
     *
     * @param revocationRegistryId      revocation registry definition id
     * @param credentialRevocationId    revocation registry credential index
     */
    fun revokeCredentialAndUpdateLedger(
        revocationRegistryId: RevocationRegistryDefinitionId,
        credentialRevocationId: String
    ): RevocationRegistryEntry

    /**
     * Creates [ProofRequest]. This function has nothing to do with Indy API, it is used just to produce well-shaped data.
     *
     * @param version           (???)
     * @param name              name of this proof request
     * @param attributes        attributes which prover needs to reveal
     * @param predicates        predicates which prover should answer
     * @param nonRevoked        time interval of [attributes] and [predicates] non-revocation
     * @param nonce             random value to distinct identical proofs
     *
     * @return                  [ProofRequest]
     */
    fun createProofRequest(
        version: String,
        name: String,
        attributes: List<CredentialFieldReference>,
        predicates: List<CredentialPredicate>,
        nonRevoked: Interval?,
        nonce: String = "123123"
    ): ProofRequest

    /**
     * Creates [ProofInfo] for provided [ProofRequest].
     *
     * @param proofRequest              proof request created by verifier
     * @param masterSecretId            master secret id
     *
     * @return                          proof and all reliable data
     */
    fun createProofFromLedgerData(
        proofRequest: ProofRequest,
        masterSecretId: String = "main"
    ): ProofInfo

    /**
     * Verifies [ProofInfo] produced by prover
     *
     * @param proofReq          proof request used by prover to create proof
     * @param proof             proof created by prover
     *
     * @return true/false       does proof valid?
     */
    fun verifyProofWithLedgerData(proofReq: ProofRequest, proof: ProofInfo): Boolean

    /**
     * Adds provided identity to whitelist
     *
     * @param identityDetails
     */
    fun addKnownIdentitiesAndStoreOnLedger(identityDetails: IdentityDetails)
}

abstract class IndyFacadeBuilder {
    var builderWalletService: WalletService? = null
    var builderLedgerService: LedgerService? = null

    fun with(walletService: WalletService): IndyFacadeBuilder {
        builderWalletService = walletService
        return this
    }

    fun with(ledgerService: LedgerService): IndyFacadeBuilder {
        builderLedgerService = ledgerService
        return this
    }

    abstract fun build(): IndyFacade
}
