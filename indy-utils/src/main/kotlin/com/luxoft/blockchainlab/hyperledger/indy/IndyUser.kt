package com.luxoft.blockchainlab.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.ledger.LedgerService
import com.luxoft.blockchainlab.hyperledger.indy.models.*
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import com.luxoft.blockchainlab.hyperledger.indy.wallet.WalletService


/**
 * The central class that encapsulates Indy SDK calls and keeps the corresponding state.
 *
 * Create one instance per each server node that deals with Indy Ledger.
 */
class IndyUser(
    override val walletService: WalletService,
    override val ledgerService: LedgerService
) : IndyFacade {

    override fun createSchemaAndStoreOnLedger(name: String, version: String, attributes: List<String>): Schema {
        val schema = walletService.createSchema(name, version, attributes)
        ledgerService.storeSchema(schema)

        return schema
    }

    override fun createCredentialDefinitionAndStoreOnLedger(
        schemaId: SchemaId,
        enableRevocation: Boolean
    ): CredentialDefinition {
        val schema = ledgerService.retrieveSchema(schemaId)
            ?: throw IndySchemaNotFoundException(schemaId, "Create credential definition has been failed")

        val credentialDefinition = walletService.createCredentialDefinition(schema, enableRevocation)
        ledgerService.storeCredentialDefinition(credentialDefinition)

        return credentialDefinition
    }

    override fun createRevocationRegistryAndStoreOnLedger(
        credentialDefinitionId: CredentialDefinitionId,
        maxCredentialNumber: Int
    ): RevocationRegistryInfo {
        val revocationRegistryInfo = walletService.createRevocationRegistry(credentialDefinitionId, maxCredentialNumber)
        ledgerService.storeRevocationRegistryDefinition(revocationRegistryInfo.definition)
        ledgerService.storeRevocationRegistryEntry(
            revocationRegistryInfo.entry,
            revocationRegistryInfo.definition.id,
            revocationRegistryInfo.definition.revocationRegistryDefinitionType
        )

        return revocationRegistryInfo
    }

    override fun createCredentialOffer(credentialDefinitionId: CredentialDefinitionId): CredentialOffer {
        return walletService.createCredentialOffer(credentialDefinitionId)
    }

    override fun createCredentialRequest(
        proverDid: String,
        credentialDefinitionId: CredentialDefinitionId,
        offer: CredentialOffer,
        masterSecretId: String
    ): CredentialRequestInfo {
        val credentialDefinition = ledgerService.retrieveCredentialDefinition(credentialDefinitionId)
            ?: throw IndyCredentialDefinitionNotFoundException(
                credentialDefinitionId,
                "Unable to create credential request"
            )

        return walletService.createCredentialRequest(proverDid, credentialDefinition, offer, masterSecretId)
    }

    override fun issueCredentialAndUpdateLedger(
        credentialRequest: CredentialRequestInfo,
        offer: CredentialOffer,
        revocationRegistryId: RevocationRegistryDefinitionId?,
        proposalProvider: () -> Map<String, CredentialValue>
    ): CredentialInfo {
        val proposalJson = SerializationUtils.anyToJSON(proposalProvider())
        val credentialInfo = walletService.issueCredential(credentialRequest, proposalJson, offer, revocationRegistryId)

        if (revocationRegistryId == null) return credentialInfo

        val revocationRegistryDefinition = ledgerService.retrieveRevocationRegistryDefinition(revocationRegistryId)
            ?: throw IndyRevRegNotFoundException(revocationRegistryId, "Unable to issue credential")

        val revocationRegistryDelta =
            SerializationUtils.jSONToAny<RevocationRegistryEntry>(credentialInfo.revocRegDeltaJson!!)

        ledgerService.storeRevocationRegistryEntry(
            revocationRegistryDelta,
            revocationRegistryDefinition.id,
            revocationRegistryDefinition.revocationRegistryDefinitionType
        )

        return credentialInfo
    }

    override fun receiveCredential(
        credentialInfo: CredentialInfo,
        credentialRequest: CredentialRequestInfo,
        offer: CredentialOffer
    ) {
        val revocationRegistryDefinitionId = credentialInfo.credential.getRevocationRegistryIdObject()

        val revocationRegistryDefinition = if (revocationRegistryDefinitionId != null)
            ledgerService.retrieveRevocationRegistryDefinition(revocationRegistryDefinitionId)
                ?: throw IndyRevRegNotFoundException(
                    revocationRegistryDefinitionId,
                    "Receive credential has been failed"
                )
        else null

        val credentialDefinition = ledgerService.retrieveCredentialDefinition(offer.getCredentialDefinitionIdObject())
            ?: throw IndyCredentialDefinitionNotFoundException(
                offer.getCredentialDefinitionIdObject(),
                "Receive credential has been failed"
            )

        walletService.receiveCredential(
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
        val revocationRegistryDefinition = ledgerService.retrieveRevocationRegistryDefinition(revocationRegistryId)
            ?: throw IndyRevRegNotFoundException(revocationRegistryId, "Revoke credential has been failed")

        val revocationRegistryEntry = walletService.revokeCredential(revocationRegistryId, credentialRevocationId)
        ledgerService.storeRevocationRegistryEntry(
            revocationRegistryEntry,
            revocationRegistryDefinition.id,
            revocationRegistryDefinition.revocationRegistryDefinitionType
        )

        return revocationRegistryEntry
    }

    override fun createProofRequest(
        version: String,
        name: String,
        attributes: List<CredentialFieldReference>,
        predicates: List<CredentialPredicate>,
        nonRevoked: Interval?,
        nonce: String
    ): ProofRequest {
        return walletService.createProofRequest(version, name, attributes, predicates, nonRevoked, nonce)
    }

    override fun createProofFromLedgerData(proofRequest: ProofRequest, masterSecretId: String): ProofInfo {
        return walletService.createProof(
            proofRequest,
            provideSchema = { ledgerService.retrieveSchema(it)!! },
            provideCredentialDefinition = { ledgerService.retrieveCredentialDefinition(it)!! },
            masterSecretId = masterSecretId
        ) { revRegId, credRevId, interval ->
            val revocationRegistryDefinition = ledgerService.retrieveRevocationRegistryDefinition(revRegId)
                ?: throw IndyRevRegNotFoundException(revRegId, "Get revocation state has been failed")

            val response = ledgerService.retrieveRevocationRegistryDelta(revRegId, interval)
                ?: throw IndyRevDeltaNotFoundException(revRegId, "Interval is $interval")
            val (timestamp, revRegDelta) = response

            walletService.createRevocationState(revocationRegistryDefinition, revRegDelta, credRevId, timestamp)
        }
    }

    override fun verifyProofWithLedgerData(proofReq: ProofRequest, proof: ProofInfo): Boolean {
        val dataUsedInProofJson = ledgerService.retrieveDataUsedInProof(proofReq, proof)

        return walletService.verifyProof(proofReq, proof, dataUsedInProofJson)
    }

    override fun addKnownIdentitiesAndStoreOnLedger(identityDetails: IdentityDetails) {
        walletService.addKnownIdentities(identityDetails)
        ledgerService.storeNym(identityDetails)
    }

    companion object : IndyFacadeBuilder() {
        override fun build(): IndyFacade {
            if (builderLedgerService == null || builderWalletService == null)
                throw RuntimeException("WalletService and LedgerService should be specified")

            return IndyUser(builderWalletService!!, builderLedgerService!!)
        }
    }

}