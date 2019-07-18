package com.luxoft.blockchainlab.corda.hyperledger.indy


import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2b.AssignPermissionsFlowB2B
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2b.CreatePairwiseFlowB2B
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2b.IssueCredentialFlowB2B
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2b.VerifyCredentialFlowB2B
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.IndyService
import com.luxoft.blockchainlab.hyperledger.indy.helpers.ConfigHelper
import com.luxoft.blockchainlab.hyperledger.indy.ledger.IndyPoolLedgerUser
import com.luxoft.blockchainlab.hyperledger.indy.wallet.IndySDKWalletUser
import io.mockk.every
import io.mockk.mockkObject
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkParameters
import net.corda.testing.node.StartedMockNode
import net.corda.testing.node.internal.cordappsForPackages
import org.junit.After
import org.junit.Before
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture
import kotlin.math.absoluteValue


/**
 * [CordaTestBase] is the base class for any test that uses mocked Corda network.
 *
 * Note: [projectReciverFlows] must be kept updated!
 * */
open class CordaTestBase {

    protected lateinit var trustee: StartedMockNode
    protected lateinit var notary: StartedMockNode

    /**
     * List of all flows that may be initiated by a message
     * */
    val projectReciverFlows = listOf(
        AssignPermissionsFlowB2B.Authority::class,
        CreatePairwiseFlowB2B.Issuer::class,
        IssueCredentialFlowB2B.Prover::class,
        VerifyCredentialFlowB2B.Prover::class
    )

    companion object {
        val TEST_GENESIS_FILE_PATH by lazy {
            this::class.java.classLoader.getResource("docker_pool_transactions_genesis.txt").file
        }
    }

    /**
     * The mocked Corda network
     * */
    protected lateinit var net: MockNetwork

    protected val parties: MutableList<StartedMockNode> = mutableListOf()

    /**
     * Recreate nodes before each test
     *
     * Usage:
     *
     *     lateinit var issuer: TestStartedNode
     *
     *     @Before
     *     createNodes() {
     *         issuer = createPartyNode(CordaX500Name("Issuer", "London", "GB"))
     *     }
     * */
    protected fun createPartyNode(legalName: CordaX500Name): StartedMockNode {
        val sessionId = Random().nextLong().absoluteValue.toString()
        val organisation = legalName.organisation

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

        val party = net.createPartyNode(legalName)

        parties.add(party)

        for (flow in projectReciverFlows) {
            party.registerInitiatedFlow(flow.java)
        }

        return party
    }

    @Before
    fun commonSetup() {
        System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "TRACE")

        val networkParameters = MockNetworkParameters(
            cordappsForAllNodes = cordappsForPackages("com.luxoft.blockchainlab.corda.hyperledger.indy"),
            networkParameters = testNetworkParameters(
                maxTransactionSize = 10485760 * 5,
                minimumPlatformVersion = 4
            )
        )
        net = MockNetwork(networkParameters)

        notary = net.defaultNotaryNode

        trustee = createPartyNode(CordaX500Name("Trustee", "London", "GB"))
    }

    @After
    fun commonTearDown() {
        try {
            for (party in parties) {
                val indyUser = party.services.cordaService(IndyService::class.java).indyUser
                // TODO: get rid of casts
                (indyUser.walletUser as IndySDKWalletUser).wallet.closeWallet().get()
                (indyUser.ledgerUser as IndyPoolLedgerUser).pool.closePoolLedger().get()
            }

            parties.clear()
        } finally {
            net.stopNodes()
        }
    }

    fun createNodes(name: String, count: Int) = (0 until count)
        .map { createPartyNode(CordaX500Name("$name-$it", "London", "GB")) }

    val flowTimeout = Duration.ofMinutes(2)

    internal fun <T> StartedMockNode.runFlow(logic: FlowLogic<T>): CompletableFuture<T> {
        val future = startFlow(logic).toCompletableFuture()
        net.runNetwork()
        return future
    }

    fun CordaX500Name.getNodeByName() =
        net.defaultNotaryNode.services.identityService.wellKnownPartyFromX500Name(this)!!
}

fun StartedMockNode.getParty() = this.info.singleIdentity()

fun StartedMockNode.getName() = getParty().name

fun StartedMockNode.getPubKey() = getParty().owningKey
