package com.luxoft.blockchainlab.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.helpers.WalletHelper
import com.luxoft.blockchainlab.hyperledger.indy.ledger.IndyPoolLedgerUser
import com.luxoft.blockchainlab.hyperledger.indy.models.*
import com.luxoft.blockchainlab.hyperledger.indy.utils.*
import com.luxoft.blockchainlab.hyperledger.indy.wallet.IndySDKWalletUser
import org.hyperledger.indy.sdk.wallet.Wallet
import org.junit.Ignore
import org.junit.Test
import java.util.*
import kotlin.math.absoluteValue


class RandomizedTest : IndyIntegrationTest() {
    val rng = Random()

    fun createIssuers(count: Int): List<Pair<Wallet, SsiUser>> {
        return (0 until count).map {
            WalletHelper.createOrTrunc("Issuer-$it", "123")
            val issuerWallet = WalletHelper.openExisting("Issuer-$it", "123")

            val issuerWalletUser = IndySDKWalletUser(issuerWallet)
            val issuerLedgerUser = IndyPoolLedgerUser(pool, issuerWalletUser.did) { issuerWalletUser.sign(it) }
            val issuerFacade = IndyUser.with(issuerWalletUser).with(issuerLedgerUser).build()

            WalletUtils.grantLedgerRights(pool, issuerWalletUser.getIdentityDetails())

            Pair(issuerWallet, issuerFacade)
        }
    }

    fun createEntities(name: String, count: Int) = (0 until count).map {
        WalletHelper.createOrTrunc("$name-$it", "123")
        val issuerWallet = WalletHelper.openExisting("$name-$it", "123")

        val issuerWalletService = IndySDKWalletUser(issuerWallet)
        val issuerLedgerService = IndyPoolLedgerUser(pool, issuerWalletService.did) { issuerWalletService.sign(it) }
        val issuerFacade = IndyUser.with(issuerWalletService).with(issuerLedgerService).build()

        Pair(issuerWallet, issuerFacade)
    }

    fun disposeEntities(entities: List<Pair<Wallet, SsiUser>>) = entities.forEach { it.first.closeWallet().get() }

    private fun SsiUser.createMetadata(
        schemaName: String,
        schemaVersion: String,
        schemaAttributes: List<String>,
        enableRevocation: Boolean,
        maxCredentialNumber: Int
    ): Triple<Schema, CredentialDefinition, RevocationRegistryInfo?> {
        val schema = createSchemaAndStoreOnLedger(schemaName, schemaVersion, schemaAttributes)
        val credDef = createCredentialDefinitionAndStoreOnLedger(schema.getSchemaIdObject(), enableRevocation)
        val revRegInfo = if (enableRevocation) {
            if (maxCredentialNumber < 2)
                throw RuntimeException("maxCredentialNumber should be at least 2")
            createRevocationRegistryAndStoreOnLedger(credDef.getCredentialDefinitionIdObject(), maxCredentialNumber)
        } else null

        return Triple(schema, credDef, revRegInfo)
    }

    private fun SsiUser.createRandomMetadata(
        attributeCountRange: IntRange,
        enableRevocation: Boolean,
        maxCredentialNumber: Int
    ): Triple<Schema, CredentialDefinition, RevocationRegistryInfo?> {
        val name = "schema-${rng.nextInt().absoluteValue}"
        val version = "${rng.nextInt().absoluteValue}.${rng.nextInt().absoluteValue}"
        val attributeCount =
            rng.nextInt().absoluteValue % (attributeCountRange.last - attributeCountRange.first) + attributeCountRange.first
        val attributes = (0 until attributeCount).map { "attribute-${rng.nextInt().absoluteValue}" }

        return createMetadata(name, version, attributes, enableRevocation, maxCredentialNumber)
    }

    private fun SsiUser.issueRandomCredential(
        to: SsiUser,
        schemaAttributes: List<String>,
        credentialDefId: CredentialDefinitionId,
        revocationRegistryId: RevocationRegistryDefinitionId?
    ): CredentialInfo {
        val rng = Random()
        val attributesToValues = mutableMapOf<String, String>()

        val offer = this.createCredentialOffer(credentialDefId)
        val request = to.createCredentialRequest(to.walletUser.getIdentityDetails().did, offer)

        val credentialInfo = this.issueCredentialAndUpdateLedger(request, offer, revocationRegistryId) {
            schemaAttributes.forEach {
                val value = rng.nextInt().absoluteValue.toString()
                attributes[it] = CredentialValue(value)

                // for test purposes
                attributesToValues[it] = value
            }
        }

        to.checkLedgerAndReceiveCredential(credentialInfo, request, offer)

        return credentialInfo
    }

    data class CredentialAndMetadata(
        val proverDid: String,
        val credentialInfo: CredentialInfo
    )

    private fun SsiUser.issueRandomSimilarCredentials(
        to: List<SsiUser>,
        attributeCountRange: IntRange,
        enableRevocation: Boolean,
        count: Int,
        maxCredentialsPerRevRegistry: Int
    ): List<CredentialAndMetadata> {
        val (schema, credDef, revRegInfo) = createRandomMetadata(
            attributeCountRange,
            enableRevocation,
            maxCredentialsPerRevRegistry
        )

        return to.map { prover ->
            (0 until count).map {
                val credential = issueRandomCredential(
                    prover,
                    schema.attributeNames,
                    credDef.getCredentialDefinitionIdObject(),
                    revRegInfo?.definition?.getRevocationRegistryIdObject()
                )
                CredentialAndMetadata(prover.walletUser.getIdentityDetails().did, credential)
            }
        }.flatten()
    }

    private fun SsiUser.issueRandomDifferentCredentials(
        to: List<SsiUser>,
        attributeCountRange: IntRange,
        enableRevocation: Boolean,
        count: Int,
        maxCredentialsPerRevRegistry: Int
    ) = to.map { prover ->
        (0 until count).map {
            val (schema, credDef, revRegInfo) = createRandomMetadata(
                attributeCountRange,
                enableRevocation,
                maxCredentialsPerRevRegistry
            )

            val credential = issueRandomCredential(
                prover,
                schema.attributeNames,
                credDef.getCredentialDefinitionIdObject(),
                revRegInfo?.definition?.getRevocationRegistryIdObject()
            )
            CredentialAndMetadata(prover.walletUser.getIdentityDetails().did, credential)
        }
    }.flatten()

    private fun SsiUser.verifyRandomAttributes(
        of: SsiUser,
        nonRevoked: Interval?,
        vararg credentials: Credential
    ): Pair<Boolean, ProofRequest> {
        val payloads = credentials.map { ProofRequestPayload.fromCredential(it) }.toTypedArray()
        val proofRequest = createRandomProofRequest(nonRevoked, *payloads)

        val proof = of.createProofFromLedgerData(proofRequest)

        val verifyStatus = verifyProofWithLedgerData(proofRequest, proof)

        return Pair(verifyStatus, proofRequest)
    }

    data class ProofState(
        val proverDid: String,
        val verifierDid: String,
        val credentials: List<CredentialInfo>,
        val proofRequest: ProofRequest
    ) {
        constructor(
            issuer: SsiUser,
            verifier: SsiUser,
            credentials: List<CredentialInfo>,
            proofRequest: ProofRequest
        ) : this(
            issuer.walletUser.getIdentityDetails().did,
            verifier.walletUser.getIdentityDetails().did,
            credentials,
            proofRequest
        )
    }

    /**
     * Typical flow is:
     *  1. N issuers issue K credentials to M provers each
     *      a) if credentials are similar, each issuer creates a single cred def and issues all credentials using it
     *      b) if credentials are different, each of them is created using different cred def
     *      c) total issued credentials count is: N*M*K
     *      d) credentials are randomized: schema attributes have random names and quantity, attribute values are also
     *          random but numeric
     *  2. J verifiers tries to verify random attributes from all credentials issued to the particular prover
     *      a) example. if the prover has 2 credentials: (name, age) and (degree, state) then random proof request will
     *          be created for attributes (name, age, degree, state)
     *      b) proof request is randomized - some attributes will be GE proved, some will be revealed, some will be
     *          ignored, attribute filters are also randomized
     *      c) if verification fails, proof state that caused the error is logged, but test continues
     *  3. If revocation enabled all issuers revoke their credentials
     *  4. Verifiers try to verify some random attributes (different than previous) again
     *      a) this verify should fail, because we did revoke credentials, but if it succeeds, proof state is logged and
     *          test continues
     *  5. Typical flow considered valid when all verifications before the revocation are successful and all verification
     *      after the revocation are failed
     */
    private fun constructTypicalFlow(
        issuerCount: Int,
        proverCount: Int,
        verifierCount: Int,
        attributeCountRange: IntRange,
        credentialCount: Int,
        similarCredentials: Boolean,
        enableRevocation: Boolean,
        maxCredentialsPerRevRegistry: Int
    ): Boolean {
        val issuers = createIssuers(issuerCount)
        val provers = createEntities("Prover", proverCount)
        val verifiers = createEntities("Verifier", verifierCount)

        try {
            val issuerToCredentials = if (similarCredentials)
                issuers.associate { (issuerWallet, issuer) ->
                    issuer to issuer.issueRandomSimilarCredentials(
                        provers.map { it.second },
                        attributeCountRange,
                        enableRevocation,
                        credentialCount,
                        maxCredentialsPerRevRegistry
                    )
                }
            else
                issuers.associate { (issuerWallet, issuer) ->
                    issuer to issuer.issueRandomDifferentCredentials(
                        provers.map { it.second },
                        attributeCountRange,
                        enableRevocation,
                        credentialCount,
                        maxCredentialsPerRevRegistry
                    )
                }

            val credentialsByProver = mutableMapOf<SsiUser, MutableList<CredentialInfo>>()
            issuerToCredentials.entries.forEach { (issuer, credentialAndMetadataList) ->
                credentialAndMetadataList.forEach { credentialAndMetadata ->
                    val prover = provers
                        .first { it.second.walletUser.getIdentityDetails().did == credentialAndMetadata.proverDid }
                        .second
                    val proverCredentials = credentialsByProver.getOrPut(prover) { mutableListOf() }

                    proverCredentials.add(credentialAndMetadata.credentialInfo)
                }
            }

            val unableToProve = mutableListOf<ProofState>()

            val nonRevoked = if (enableRevocation) Interval.allTime() else null

            verifiers.forEach { (verifierWallet, verifier) ->
                credentialsByProver.entries.forEach { (prover, credentials) ->
                    val (proofStatus, proofRequest) = verifier.verifyRandomAttributes(
                        prover,
                        nonRevoked,
                        *(credentials.map { it.credential }.toTypedArray())
                    )

                    if (!proofStatus)
                        unableToProve.add(
                            ProofState(prover, verifier, credentials, proofRequest)
                        )
                }
            }

            if (unableToProve.isNotEmpty()) {
                println("------- Failed proofs -------")
                println(SerializationUtils.anyToJSON(unableToProve))
                println("-----------------------------")
            }

            val ableToProve = mutableListOf<ProofState>()

            if (enableRevocation) {
                issuerToCredentials.forEach { (issuer, credentialAndMetadataList) ->
                    credentialAndMetadataList.map { it.credentialInfo }.forEach { credentialInfo ->
                        issuer.revokeCredentialAndUpdateLedger(
                            credentialInfo.credential.getRevocationRegistryIdObject()!!,
                            credentialInfo.credRevocId!!
                        )
                    }
                }

                verifiers.forEach { (verifierWallet, verifier) ->
                    credentialsByProver.entries.forEach { (prover, credentials) ->
                        val (proofStatus, proofRequest) = verifier.verifyRandomAttributes(
                            prover,
                            Interval.allTime(),
                            *(credentials.map { it.credential }.toTypedArray())
                        )

                        if (proofStatus)
                            ableToProve.add(
                                ProofState(prover, verifier, credentials, proofRequest)
                            )
                    }
                }

                if (ableToProve.isNotEmpty()) {
                    println("------- Failed proofs after revocation -------")
                    println(SerializationUtils.anyToJSON(ableToProve))
                    println("----------------------------------------------")
                }
            }
            return unableToProve.isEmpty() && ableToProve.isEmpty()

        } finally {
            disposeEntities(issuers)
            disposeEntities(provers)
            disposeEntities(verifiers)
        }
    }

    @Test
    fun `1 issuer 1 prover 1 verifier 1 credential without revocation`() {
        assert(
            constructTypicalFlow(1, 1, 1, (2..4), 1, false, false, 2)
        )
    }

    @Test
    fun `1 issuer 1 prover 1 verifier 1 credential with revocation`() {
        assert(
            constructTypicalFlow(1, 1, 1, (2..4), 1, false, true, 2)
        )
    }

    @Test
    fun `1 issuer 1 prover 1 verifier 2 similar credentials without revocation`() {
        assert(
            constructTypicalFlow(1, 1, 1, (2..4), 2, true, false, 2)
        )
    }

    @Ignore("Sometimes it fails because it unable to find attribute in a credential, otherwise it fails because before revocation proof is invalid")
    @Test
    fun `1 issuer 1 prover 1 verifier 2 similar credentials with revocation`() {
        assert(
            constructTypicalFlow(1, 1, 1, (2..4), 2, true, true, 2)
        )
    }

    @Test
    fun `1 issuer 1 prover 1 verifier 2 different credentials without revocation`() {
        assert(
            constructTypicalFlow(1, 1, 1, (2..4), 2, false, false, 2)
        )
    }

    @Test
    fun `1 issuer 1 prover 1 verifier 2 different credentials with revocation`() {
        assert(
            constructTypicalFlow(1, 1, 1, (2..4), 2, false, true, 2)
        )
    }
}
