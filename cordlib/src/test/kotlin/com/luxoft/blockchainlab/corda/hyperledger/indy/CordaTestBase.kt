package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.CreateCredentialDefinitionFlow
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.CreateRevocationRegistryFlow
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.CreateSchemaFlow
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.RevokeCredentialFlow
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2b.AssignPermissionsFlowB2B
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2b.CreatePairwiseFlowB2B
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2b.IssueCredentialFlowB2B
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2b.VerifyCredentialFlowB2B
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.IndyService
import com.luxoft.blockchainlab.hyperledger.indy.helpers.ConfigHelper
import com.luxoft.blockchainlab.hyperledger.indy.helpers.WalletHelper
import com.luxoft.blockchainlab.hyperledger.indy.ledger.IndyPoolLedgerService
import com.luxoft.blockchainlab.hyperledger.indy.models.*
import com.luxoft.blockchainlab.hyperledger.indy.utils.*
import com.luxoft.blockchainlab.hyperledger.indy.wallet.IndySDKWalletService
import io.mockk.every
import io.mockk.mockkObject
import net.corda.core.concurrent.CordaFuture
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.FlowStateMachine
import net.corda.core.utilities.getOrThrow
import net.corda.node.internal.StartedNode
import net.corda.node.services.api.StartedNodeServices
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.InternalMockNetwork.MockNode
import net.corda.testing.node.internal.MockNodeArgs
import net.corda.testing.node.internal.newContext
import org.junit.After
import org.junit.Before
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.Duration
import java.util.*
import kotlin.math.absoluteValue


/**
 * [CordaTestBase] is the base class for any test that uses mocked Corda network.
 *
 * Note: [projectReciverFlows] must be kept updated!
 * */
open class CordaTestBase {

    protected lateinit var trustee: StartedNode<MockNode>

    /**
     * List of all flows that may be initiated by a message
     * */
    val projectReciverFlows = listOf(
        AssignPermissionsFlowB2B.Authority::class,
        CreatePairwiseFlowB2B.Issuer::class,
        IssueCredentialFlowB2B.Prover::class,
        VerifyCredentialFlowB2B.Prover::class
    )

    /**
     * The mocked Corda network
     * */
    protected lateinit var net: InternalMockNetwork
        private set

    protected val parties: MutableList<StartedNode<MockNode>> = mutableListOf()

    /**
     * Shares permissions from [authority] to [issuer]
     * This action is needed to implement authority chains
     *
     * @param issuer            a node that needs permissions
     * @param authority         a node that can share permissions
     */
    protected fun setPermissions(issuer: StartedNode<MockNode>, authority: StartedNode<MockNode>) {
        val permissionsFuture = issuer.services.startFlow(
            AssignPermissionsFlowB2B.Issuer(authority = authority.info.singleIdentity().name, role = "TRUSTEE")
        ).resultFuture

        permissionsFuture.getOrThrow(Duration.ofSeconds(30))
    }

    /**
     * Recreate nodes before each test
     *
     * Usage:
     *
     *     lateinit var issuer: StartedNode<MockNode>
     *
     *     @Before
     *     createNodes() {
     *         issuer = createPartyNode(CordaX500Name("Issuer", "London", "GB"))
     *     }
     * */
    protected fun createPartyNode(legalName: CordaX500Name): StartedNode<MockNode> {
        val party = net.createPartyNode(legalName)

        parties.add(party)

        for (flow in projectReciverFlows) {
            party.registerInitiatedFlow(flow.java)
        }

        return party
    }

    /**
     * Substitutes [StartedNodeServices.startFlow] method to run mocked Corda flows.
     *
     * Usage:
     *
     *     val did = store.services.startFlow(GetDidFlowB2B.Initiator(name)).resultFuture.get()
     */
    protected fun <T> StartedNodeServices.startFlow(logic: FlowLogic<T>): FlowStateMachine<T> {
        val machine = startFlow(logic, newContext()).getOrThrow()

        return object : FlowStateMachine<T> by machine {
            override val resultFuture: CordaFuture<T>
                get() {
                    net.runNetwork()
                    return machine.resultFuture
                }
        }
    }

    @Before
    fun commonSetup() {
        System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "OFF")

        net = InternalMockNetwork(
            cordappPackages = listOf("com.luxoft.blockchainlab.corda.hyperledger.indy"),
            networkParameters = testNetworkParameters(maxTransactionSize = 10485760 * 5),
            defaultFactory = CordaTestBase::MockIndyNode
        )
    }

    open class MockIndyNode(args: MockNodeArgs) : InternalMockNetwork.MockNode(args) {

        companion object {
            val TEST_GENESIS_FILE_PATH by lazy {
                this::class.java.classLoader.getResource("docker_pool_transactions_genesis.txt").file
            }
        }

        private val organisation: String = args.config.myLegalName.organisation

        override fun start(): StartedNode<MockNode> {
            val sessionId = Random().nextLong().absoluteValue.toString()
            mockkObject(ConfigHelper)

            every { ConfigHelper.getPoolName() } returns organisation + sessionId
            every { ConfigHelper.getGenesisPath() } returns TEST_GENESIS_FILE_PATH
            every { ConfigHelper.getWalletName() } returns organisation + sessionId
            every { ConfigHelper.getWalletPassword() } returns "password"
            every { ConfigHelper.getRole() } returns ""

            if (organisation == "Trustee") {
                every { ConfigHelper.getDid() } returns "V4SGRU86Z58d6TV7PBUe6f"
                every { ConfigHelper.getRole() } returns "trustee"
                every { ConfigHelper.getSeed() } returns "000000000000000000000000Trustee1"
            }

            return super.start()
        }
    }

    @After
    fun commonTearDown() {
        try {
            for (party in parties) {
                val indyUser = party.services.cordaService(IndyService::class.java).indyUser
                // TODO: get rid of casts
                (indyUser.walletService as IndySDKWalletService).wallet.closeWallet().get()
                (indyUser.ledgerService as IndyPoolLedgerService).pool.closePoolLedger().get()
            }

            parties.clear()
        } finally {
            net.stopNodes()
        }
    }

    fun createNodes(name: String, count: Int) = (0 until count)
        .map { createPartyNode(CordaX500Name("$name-$it", "London", "GB")) }

    fun createIssuerNodes(trustee: StartedNode<MockNode>, count: Int) = (0 until count)
        .map { createPartyNode(CordaX500Name("Issuer-$it", "London", "GB")) }
        .map { setPermissions(it, trustee); it }


    data class ProofRequestPayload(
        val attributeValues: Map<String, String>,
        val schemaId: SchemaId,
        var credDefId: CredentialDefinitionId,
        var schemaIssuerDid: String,
        var schemaName: String,
        var schemaVersion: String,
        var issuerDid: String
    ) {
        companion object {
            fun create(
                attributeValues: Map<String, String>,
                issuerDid: String,
                schemaId: SchemaId,
                credentialDefId: CredentialDefinitionId
            ) = ProofRequestPayload(
                attributeValues,
                schemaId,
                credentialDefId,
                schemaId.did,
                schemaId.name,
                schemaId.version,
                issuerDid
            )
        }
    }

    fun ProofRequest.applyPayloadRandomly(payload: ProofRequestPayload, nonRevoked: Interval?) {
        val rng = Random()

        payload.attributeValues.entries.forEach attributeLoop@ { (key, value) ->
            val proveGE = rng.nextBoolean()
            if (proveGE) {
                val valueInt = value.toInt()
                val greaterThan = rng.nextInt().absoluteValue % valueInt

                proveGreaterThan(key, greaterThan) {
                    FilterProperty.values().forEach filterLoop@ {
                        val skip = rng.nextBoolean()
                        if (skip) return@filterLoop

                        when (it) {
                            FilterProperty.CredentialDefinitionId -> it shouldBe payload.credDefId.toString()
                            FilterProperty.SchemaId -> it shouldBe payload.schemaId.toString()
                            FilterProperty.IssuerDid -> it shouldBe payload.issuerDid
                            FilterProperty.SchemaIssuerDid -> it shouldBe payload.schemaIssuerDid
                            FilterProperty.SchemaName -> it shouldBe payload.schemaName
                            FilterProperty.SchemaVersion -> it shouldBe payload.schemaVersion
                        }
                    }
                }
            } else {
                reveal(key) {
                    FilterProperty.values().forEach filterLoop@ {
                        val skip = rng.nextBoolean()
                        if (skip) return@filterLoop

                        when (it) {
                            FilterProperty.Value -> it shouldBe value
                            FilterProperty.CredentialDefinitionId -> it shouldBe payload.credDefId.toString()
                            FilterProperty.SchemaId -> it shouldBe payload.schemaId.toString()
                            FilterProperty.IssuerDid -> it shouldBe payload.issuerDid
                            FilterProperty.SchemaIssuerDid -> it shouldBe payload.schemaIssuerDid
                            FilterProperty.SchemaName -> it shouldBe payload.schemaName
                            FilterProperty.SchemaVersion -> it shouldBe payload.schemaVersion
                        }
                    }
                }
            }

            if (nonRevoked != null) proveNonRevocation(nonRevoked)
        }
    }

    fun createRandomProofRequest(
        attributeValues: Map<String, String>,
        issuerDid: String,
        schemaId: SchemaId,
        credentialDefId: CredentialDefinitionId,
        nonRevoked: Interval?
    ): ProofRequest {
        val rng = Random()
        val name = "proof-request-${rng.nextInt().absoluteValue}"
        val version = "${rng.nextInt().absoluteValue}.${rng.nextInt().absoluteValue}"

        val prPayload = ProofRequestPayload.create(attributeValues, issuerDid, schemaId, credentialDefId)

        return proofRequest(name, version) {
            applyPayloadRandomly(prPayload, nonRevoked)
        }
    }

    data class CredentialAndMetadata(
        val schema: Schema,
        val credentialDefinition: CredentialDefinition,
        val revocationRegistryInfo: RevocationRegistryInfo?,
        val idAndValues: Pair<String, Map<String, String>>,
        val prover: Pair<CordaX500Name, String>
    ) {
        constructor(
            schema: Schema,
            credentialDefinition: CredentialDefinition,
            revocationRegistryInfo: RevocationRegistryInfo?,
            idAndValues: Pair<String, Map<String, String>>,
            prover: StartedNode<MockNode>
        ) : this(schema, credentialDefinition, revocationRegistryInfo, idAndValues, Pair(prover.getName(), prover.getPartyDid()))
    }

    data class ProofState(
        val issuerNameAndDid: Pair<CordaX500Name, String>,
        val verifierNameAndDid: Pair<CordaX500Name, String>,
        val credentialAndMetadata: CredentialAndMetadata,
        val proofRequest: ProofRequest
    ) {
        constructor(
            issuer: StartedNode<MockNode>,
            verifier: StartedNode<MockNode>,
            credentialAndMetadata: CredentialAndMetadata,
            proofRequest: ProofRequest
        ) : this(
            Pair(issuer.getName(), issuer.getPartyDid()),
            Pair(verifier.getName(), verifier.getPartyDid()),
            credentialAndMetadata,
            proofRequest
        )
    }

    /**
     * N issuers, each issues M random credentials to each of K provers, then L verifiers try to verify these credentials randomly
     * revocation is optional, but if it takes place, all credentials should be revoked after all
     * credentials can be similar (issued using single cred def) or different (issued using multiple cred defs)
     */
    fun constructTypicalFlow(
        issuerCount: Int,
        proverCount: Int,
        verifierCount: Int,
        credentialCount: Int,
        similarCredentials: Boolean,
        enableRevocation: Boolean,
        maxCredentialsPerRevRegistry: Int
    ): Boolean {
        val issuers = createIssuerNodes(trustee, issuerCount)
        val provers = createNodes("Prover", proverCount)

        val issuerToIssuedCredentials = mutableMapOf<StartedNode<MockNode>, MutableList<CredentialAndMetadata>>()

        issuers.forEach { issuer ->
            val credentials = issuerToIssuedCredentials.getOrPut(issuer) { mutableListOf() }

            if (similarCredentials)
                credentials.addAll(issuer.issueRandomSimilarCredentials(provers, enableRevocation, credentialCount, maxCredentialsPerRevRegistry))
            else
                credentials.addAll(issuer.issueRandomDifferentCredentials(provers, enableRevocation, credentialCount, maxCredentialsPerRevRegistry))
        }

        val verifiers = createNodes("Verifier", verifierCount)

        val unableToProve = mutableListOf<ProofState>()

        val nonRevoked = if (enableRevocation) Interval.now() else null

        verifiers.forEach { verifier ->
            issuerToIssuedCredentials.entries.forEach { (issuer, credentialAndMetadataList) ->
                credentialAndMetadataList.forEach { credentialAndMetadata ->
                    val pr = createRandomProofRequest(
                        credentialAndMetadata.idAndValues.second,
                        issuer.getPartyDid(),
                        credentialAndMetadata.schema.getSchemaIdObject(),
                        credentialAndMetadata.credentialDefinition.getCredentialDefinitionIdObject(),
                        nonRevoked
                    )

                    val prover = provers.first { it.getName() == credentialAndMetadata.prover.first }
                    val (proofId, proofValid) = verifier.verify(prover, pr)

                    if (!proofValid)
                        unableToProve.add(ProofState(issuer, verifier, credentialAndMetadata, pr))
                }
            }
        }

        if (unableToProve.isNotEmpty()) {
            println("------- Failed proofs -------")
            println(SerializationUtils.anyToJSON(unableToProve))
            println("-----------------------------")
        }

        val ableToProve = mutableListOf<ProofState>()

        if (enableRevocation) {
            issuerToIssuedCredentials.entries.forEach { (issuer, credentialAndMetadataList) ->
                credentialAndMetadataList.forEach { credentialAndMetadata ->
                    issuer.revoke(credentialAndMetadata.idAndValues.first)
                }
            }

            verifiers.forEach { verifier ->
                issuerToIssuedCredentials.entries.forEach { (issuer, credentialAndMetadataList) ->
                    credentialAndMetadataList.forEach { credentialAndMetadata ->
                        val pr = createRandomProofRequest(
                            credentialAndMetadata.idAndValues.second,
                            issuer.getPartyDid(),
                            credentialAndMetadata.schema.getSchemaIdObject(),
                            credentialAndMetadata.credentialDefinition.getCredentialDefinitionIdObject(),
                            Interval.now()
                        )

                        val prover = provers.first { it.getName() == credentialAndMetadata.prover.first }
                        val (proofId, proofValid) = verifier.verify(prover, pr)

                        if (proofValid)
                            ableToProve.add(ProofState(issuer, verifier, credentialAndMetadata, pr))
                    }
                }
            }

            if (ableToProve.isNotEmpty()) {
                println("------- Failed proofs after revocation -------")
                println(SerializationUtils.anyToJSON(ableToProve))
                println("----------------------------------------------")
            }
        }

        return unableToProve.isEmpty() && ableToProve.isEmpty()
    }
}
