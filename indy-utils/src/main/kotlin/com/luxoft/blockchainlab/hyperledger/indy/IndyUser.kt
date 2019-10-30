package com.luxoft.blockchainlab.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.ledger.LedgerUser
import com.luxoft.blockchainlab.hyperledger.indy.models.*
import com.luxoft.blockchainlab.hyperledger.indy.utils.ExtraQueryBuilder
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import com.luxoft.blockchainlab.hyperledger.indy.wallet.IndySDKWalletUser
import com.luxoft.blockchainlab.hyperledger.indy.wallet.WalletUser
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit


/**
 * The central class that encapsulates Indy SDK calls and keeps the corresponding state.
 * This is implementation of [SsiUser] so it should support every valid [LedgerUser] and [WalletUser]
 *  implementation.
 */
class IndyUser(
    override val walletUser: WalletUser,
    override val ledgerUser: LedgerUser,
    //Required for android because it will cause exception in native code and app crash
    createDefaultMasterSecret: Boolean = true
) : SsiUser {

    init {
        // we create some master secret by default, but user can create and manage them manually
        if (createDefaultMasterSecret)
            walletUser.createMasterSecret(DEFAULT_MASTER_SECRET_ID)
    }

    override fun createSchemaAndStoreOnLedger(name: String, version: String, attributes: List<String>): Schema {
        val schema = walletUser.createSchema(name, version, attributes)
        ledgerUser.storeSchema(schema)

        return schema
    }

    override fun createCredentialDefinitionAndStoreOnLedger(
        schemaId: SchemaId,
        enableRevocation: Boolean
    ): CredentialDefinition {
        val schema = ledgerUser.retrieveSchema(schemaId)
            ?: throw IndySchemaNotFoundException(schemaId, "Create credential definition has been failed")

        val credentialDefinition = walletUser.createCredentialDefinition(schema, enableRevocation)
        ledgerUser.storeCredentialDefinition(credentialDefinition)

        return credentialDefinition
    }

    /**
     * Returns revocation registry info ([RevocationRegistryInfo]) for credential definition if there's one on ledger.
     * Otherwise returns null
     *
     * @param credentialDefinitionId    credential definition id
     *
     * @return                          created
     */
    override fun getRevocationRegistryDefinition(
            credentialDefinitionId: CredentialDefinitionId
    ): RevocationRegistryDefinition? {

        val revRegId = credentialDefinitionId.getPossibleRevocationRegistryDefinitionId(IndySDKWalletUser.REVOCATION_TAG)
        return ledgerUser.retrieveRevocationRegistryDefinition(revRegId)
    }

    /**
     * Creates revocation registry for credential definition if there's no one in ledger
     * (usable only for those credential definition for which enableRevocation = true)
     *
     * @param credentialDefinitionId    credential definition id
     * @param maxCredentialNumber       maximum number of credentials which can be issued for this credential definition
     *                                  (example) driver agency can produce only 1000 driver licences per year
     *
     * @return                          created
     */
    override fun createRevocationRegistryAndStoreOnLedger(
        credentialDefinitionId: CredentialDefinitionId,
        maxCredentialNumber: Int
    ): RevocationRegistryInfo {
        val revocationRegistryInfo = walletUser.createRevocationRegistry(credentialDefinitionId, maxCredentialNumber)
        ledgerUser.storeRevocationRegistryDefinition(revocationRegistryInfo.definition)
        ledgerUser.storeRevocationRegistryEntry(
            revocationRegistryInfo.entry,
            revocationRegistryInfo.definition.id,
            revocationRegistryInfo.definition.revocationRegistryDefinitionType
        )

        return revocationRegistryInfo
    }

    override fun createCredentialOffer(credentialDefinitionId: CredentialDefinitionId): CredentialOffer {
        return walletUser.createCredentialOffer(credentialDefinitionId)
    }

    override fun createCredentialRequest(
        proverDid: String,
        offer: CredentialOffer,
        masterSecretId: String
    ): CredentialRequestInfo {
        val credentialDefinitionId = offer.getCredentialDefinitionIdObject()

        val credentialDefinition = ledgerUser.retrieveCredentialDefinition(credentialDefinitionId)
            ?: throw IndyCredentialDefinitionNotFoundException(
                credentialDefinitionId,
                "Unable to create credential request"
            )

        return walletUser.createCredentialRequest(proverDid, credentialDefinition, offer, masterSecretId)
    }

    override fun issueCredentialAndUpdateLedger(
        credentialRequest: CredentialRequestInfo,
        offer: CredentialOffer,
        revocationRegistryId: RevocationRegistryDefinitionId?,
        proposalFiller: CredentialProposal.() -> Unit
    ): CredentialInfo {
        val proposal = CredentialProposal()
        proposal.proposalFiller()

        val proposalJson = SerializationUtils.anyToJSON(proposal.attributes)
        val credentialInfo = walletUser.issueCredential(credentialRequest, proposalJson, offer, revocationRegistryId)

        if (revocationRegistryId == null) return credentialInfo

        val revocationRegistryDefinition = ledgerUser.retrieveRevocationRegistryDefinition(revocationRegistryId)
            ?: throw IndyRevRegNotFoundException(revocationRegistryId, "Unable to issue credential")

        val revocationRegistryDelta =
            SerializationUtils.jSONToAny<RevocationRegistryEntry>(credentialInfo.revocRegDeltaJson!!)

        ledgerUser.storeRevocationRegistryEntry(
            revocationRegistryDelta,
            revocationRegistryDefinition.id,
            revocationRegistryDefinition.revocationRegistryDefinitionType
        )

        return credentialInfo
    }

    override fun checkLedgerAndReceiveCredential(
        credentialInfo: CredentialInfo,
        credentialRequest: CredentialRequestInfo,
        offer: CredentialOffer
    ): String {
        val revocationRegistryDefinitionId = credentialInfo.credential.getRevocationRegistryIdObject()

        val revocationRegistryDefinition = if (revocationRegistryDefinitionId != null)
            ledgerUser.retrieveRevocationRegistryDefinition(revocationRegistryDefinitionId)
                ?: throw IndyRevRegNotFoundException(
                    revocationRegistryDefinitionId,
                    "Receive credential has been failed"
                )
        else null

        val credentialDefinition = ledgerUser.retrieveCredentialDefinition(offer.getCredentialDefinitionIdObject())
            ?: throw IndyCredentialDefinitionNotFoundException(
                offer.getCredentialDefinitionIdObject(),
                "Receive credential has been failed"
            )

        return walletUser.receiveCredential(
            credentialInfo,
            credentialRequest,
            offer,
            credentialDefinition,
            revocationRegistryDefinition
        )
    }

    override fun revokeCredentialAndUpdateLedger(
        revocationRegistryId: RevocationRegistryDefinitionId,
        credentialRevocationId: String
    ): RevocationRegistryEntry {
        val revocationRegistryDefinition = ledgerUser.retrieveRevocationRegistryDefinition(revocationRegistryId)
            ?: throw IndyRevRegNotFoundException(revocationRegistryId, "Revoke credential has been failed")

        val revocationRegistryEntry = walletUser.revokeCredential(revocationRegistryId, credentialRevocationId)
        ledgerUser.storeRevocationRegistryEntry(
            revocationRegistryEntry,
            revocationRegistryDefinition.id,
            revocationRegistryDefinition.revocationRegistryDefinitionType
        )

        return revocationRegistryEntry
    }

    override fun createProofFromLedgerData(proofRequest: ProofRequest, masterSecretId: String, init: ExtraQueryBuilder.() -> Unit): ProofInfo {
        val builder = ExtraQueryBuilder()
        builder.init()
        val builderEntries = builder.attributes.entries
        val extraQuery = if (builderEntries.isEmpty()) null else builderEntries.associate { it.key to it.value.toMap() }

        return walletUser.createProof(
            proofRequest,
            provideSchema = { ledgerUser.retrieveSchema(it)!! },
            provideCredentialDefinition = { ledgerUser.retrieveCredentialDefinition(it)!! },
            masterSecretId = masterSecretId,
            extraQuery = extraQuery
        ) { revRegId, credRevId, interval, revRegDuplicate ->
            val atLeastOneSecondDelay =
                if (revRegDuplicate) CompletableFuture.runAsync { TimeUnit.SECONDS.sleep(1L) }
                else CompletableFuture.completedFuture(Unit)
            val revocationRegistryDefinition = ledgerUser.retrieveRevocationRegistryDefinition(revRegId)
                ?: throw IndyRevRegNotFoundException(revRegId, "Get revocation state has been failed")

            val response = ledgerUser.retrieveRevocationRegistryDelta(revRegId, interval)
                ?: throw IndyRevDeltaNotFoundException(revRegId, "Interval is $interval")
            val (_, revRegDelta) = response

            atLeastOneSecondDelay.get()
            walletUser.createRevocationState(revocationRegistryDefinition, revRegDelta, credRevId, Timestamp.now())
        }
    }

    override fun verifyProofWithLedgerData(proofReq: ProofRequest, proof: ProofInfo): Boolean {
        val dataUsedInProofJson = ledgerUser.retrieveDataUsedInProof(proofReq, proof)

        return walletUser.verifyProof(proofReq, proof, dataUsedInProofJson)
    }

    override fun addKnownIdentitiesAndStoreOnLedger(identityDetails: IdentityDetails) {
        walletUser.addKnownIdentities(identityDetails)
        ledgerUser.storeNym(identityDetails)
    }

    companion object : IndyFacadeBuilder() {
        override fun build(): SsiUser {
            if (builderLedgerUser == null || builderWalletUser == null)
                throw RuntimeException("WalletUser and LedgerUser should be specified")

            return IndyUser(builderWalletUser!!, builderLedgerUser!!)
        }
    }

}
