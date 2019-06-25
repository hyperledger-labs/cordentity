package com.luxoft.blockchainlab.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.ledger.LedgerUser
import com.luxoft.blockchainlab.hyperledger.indy.models.*
import com.luxoft.blockchainlab.hyperledger.indy.utils.ExtraQueryBuilder
import com.luxoft.blockchainlab.hyperledger.indy.wallet.WalletUser

const val DEFAULT_MASTER_SECRET_ID = "main"

/**
 * This is the top-level interface that encapsulates work that should be done by [WalletUser] and [LedgerUser]
 *  cooperatively. Everything is abstracted as much as possible, so every valid service implementation should work.
 */
interface SsiUser {
    val walletUser: WalletUser
    val ledgerUser: LedgerUser

    /**
     * Creates [Schema] using [WalletUser] and stores it using [LedgerUser]
     *
     * @param name [String] - schema name
     * @param version [String] - schema version in format "d.d.d"
     * @param attributes [List] of [String] - list of schema's attribute names
     *
     * @return [Schema]
     */
    fun createSchemaAndStoreOnLedger(name: String, version: String, attributes: List<String>): Schema

    /**
     * Creates [CredentialDefinition] using [WalletUser] and stores it using [LedgerUser]
     *
     * @param schemaId [SchemaId] - id of schema paired with this credential definition
     * @param enableRevocation [Boolean] - flag if you need revocation be enabled
     *
     * @return [CredentialDefinition]
     */
    fun createCredentialDefinitionAndStoreOnLedger(schemaId: SchemaId, enableRevocation: Boolean): CredentialDefinition

    /**
     * Creates [RevocationRegistryDefinition] and first [RevocationRegistryEntry] using [WalletUser] and stores it
     * using [LedgerUser]
     *
     * @param credentialDefinitionId [CredentialDefinitionId] - id of credential definition paired with this revocation
     *  registry
     * @param maxCredentialNumber [Int] - maximum credential count that this revocation registry can hold
     *
     * @return [RevocationRegistryInfo] - combination of [RevocationRegistryDefinition] and [RevocationRegistryEntry]
     */
    fun createRevocationRegistryAndStoreOnLedger(
        credentialDefinitionId: CredentialDefinitionId,
        maxCredentialNumber: Int
    ): RevocationRegistryInfo

    /**
     * Returns revocation registry info ([RevocationRegistryInfo]) for credential definition if there's one on ledger.
     * Otherwise returns null
     *
     * @param credentialDefinitionId    credential definition id
     *
     * @return                          created
     */
    fun getRevocationRegistryDefinition(
            credentialDefinitionId: CredentialDefinitionId
    ): RevocationRegistryDefinition?

    /**
     * Creates [CredentialOffer] using [WalletUser]
     *
     * @param credentialDefinitionId [CredentialDefinitionId]
     *
     * @return [CredentialOffer]
     */
    fun createCredentialOffer(credentialDefinitionId: CredentialDefinitionId): CredentialOffer

    /**
     * Creates [CredentialRequest] using [WalletUser]
     *
     * @param proverDid [String]
     * @param offer [CredentialOffer]
     * @param masterSecretId [String]
     *
     * @return [CredentialRequestInfo] - [CredentialRequest] and all related data
     */
    fun createCredentialRequest(
        proverDid: String,
        offer: CredentialOffer,
        masterSecretId: String = DEFAULT_MASTER_SECRET_ID
    ): CredentialRequestInfo

    /**
     * Issues [Credential] by [CredentialRequest] and [CredentialOffer] using [WalletUser].
     * If revocation is enabled it will hold one of [maxCredentialNumber].
     *
     * @param credentialRequest [CredentialRequestInfo] - [CredentialRequest] and all reliable info
     * @param offer [CredentialOffer] - credential offer
     * @param revocationRegistryId [RevocationRegistryDefinitionId] or [null] - revocation registry definition id
     * @param proposalFiller [CredentialProposal].() -> [Unit] - [CredentialProposal] initializer - use attributes["${name}"] inside
     *
     * @return [CredentialInfo] - credential and all reliable data
     */
    fun issueCredentialAndUpdateLedger(
        credentialRequest: CredentialRequestInfo,
        offer: CredentialOffer,
        revocationRegistryId: RevocationRegistryDefinitionId?,
        proposalFiller: CredentialProposal.() -> Unit
    ): CredentialInfo

    /**
     * Stores [Credential] in prover's wallet gathering data using [LedgerUser]
     *
     * @param credentialInfo [CredentialInfo] - credential and all reliable data
     * @param credentialRequest [CredentialRequestInfo] - credential request and all reliable data
     * @param offer [CredentialOffer]
     *
     * @return local UUID of the stored credential in the prover's wallet
     */
    fun checkLedgerAndReceiveCredential(
        credentialInfo: CredentialInfo,
        credentialRequest: CredentialRequestInfo,
        offer: CredentialOffer
    ): String

    /**
     * Revokes previously issued [Credential] using [WalletUser] and [LedgerUser]
     *
     * @param revocationRegistryId [RevocationRegistryDefinitionId] - revocation registry definition id
     * @param credentialRevocationId [String] - revocation registry credential index
     */
    fun revokeCredentialAndUpdateLedger(
        revocationRegistryId: RevocationRegistryDefinitionId,
        credentialRevocationId: String
    ): RevocationRegistryEntry

    /**
     * Creates [ProofInfo] for provided [ProofRequest].
     *
     * @param proofRequest [ProofRequest] - proof request created by verifier
     * @param masterSecretId [String]
     * @param init: [ExtraQueryBuilder].() -> [Unit] - extra query initializer, use attributes["${name}"] = [wql] DSL to create it
     *
     * @return [ProofInfo] - proof and all reliable data
     */
    fun createProofFromLedgerData(
        proofRequest: ProofRequest,
        masterSecretId: String = DEFAULT_MASTER_SECRET_ID,
        init: ExtraQueryBuilder.() -> Unit = {}
    ): ProofInfo

    /**
     * Verifies [ProofInfo] produced by prover
     *
     * @param proofReq [ProofRequest] - proof request used by prover to create proof, use [proofRequest] DSL to create it
     * @param proof [ProofInfo] - proof created by prover
     *
     * @return [Boolean] - is proof valid?
     */
    fun verifyProofWithLedgerData(proofReq: ProofRequest, proof: ProofInfo): Boolean

    /**
     * Adds provided identity to whitelist and stores this info on ledger
     *
     * @param identityDetails [IdentityDetails]
     */
    fun addKnownIdentitiesAndStoreOnLedger(identityDetails: IdentityDetails)
}

/**
 * Builder for some [SsiUser] implementation
 */
abstract class IndyFacadeBuilder {
    var builderWalletUser: WalletUser? = null
    var builderLedgerUser: LedgerUser? = null

    fun with(walletUser: WalletUser): IndyFacadeBuilder {
        builderWalletUser = walletUser
        return this
    }

    fun with(ledgerUser: LedgerUser): IndyFacadeBuilder {
        builderLedgerUser = ledgerUser
        return this
    }

    /**
     * Implement this method, but be sure that you've checked presence of [WalletUser] and [LedgerUser]
     */
    abstract fun build(): SsiUser
}
