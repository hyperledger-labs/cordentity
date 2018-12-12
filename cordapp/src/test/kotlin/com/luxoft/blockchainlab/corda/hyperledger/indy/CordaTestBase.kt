package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.AssignPermissionsFlow
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.CreatePairwiseFlow
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.IssueCredentialFlow
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.VerifyCredentialFlow
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.IndyService
import com.luxoft.blockchainlab.hyperledger.indy.utils.PoolManager
import com.natpryce.konfig.Configuration
import com.natpryce.konfig.ConfigurationMap
import com.natpryce.konfig.TestConfigurationsProvider
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
import net.corda.testing.node.internal.newContext
import org.junit.After
import org.junit.Before
import java.time.Duration
import java.util.*
import kotlin.math.absoluteValue


/**
 * [CordaTestBase] is the base class for any test that uses mocked Corda network.
 *
 * Note: [projectReciverFlows] must be kept updated!
 * */
open class CordaTestBase {

    /**
     * List of all flows that may be initiated by a message
     * */
    val projectReciverFlows = listOf(
        AssignPermissionsFlow.Authority::class,
        CreatePairwiseFlow.Issuer::class,
        IssueCredentialFlow.Prover::class,
        VerifyCredentialFlow.Prover::class
    )

    /**
     * The mocked Corda network
     * */
    protected lateinit var net: InternalMockNetwork
        private set

    private val parties: MutableList<StartedNode<MockNode>> = mutableListOf()

    protected val random = Random()

    /**
     * Shares permissions from [authority] to [issuer]
     * This action is needed to implement authority chains
     *
     * @param issuer            a node that needs permissions
     * @param authority         a node that can share permissions
     */
    protected fun setPermissions(issuer: StartedNode<MockNode>, authority: StartedNode<MockNode>) {
        val permissionsFuture = issuer.services.startFlow(
            AssignPermissionsFlow.Issuer(authority = authority.info.singleIdentity().name, role = "TRUSTEE")
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
     *     val did = store.services.startFlow(GetDidFlow.Initiator(name)).resultFuture.get()
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
        TestConfigurationsProvider.provider = object : TestConfigurationsProvider {
            override fun getConfig(name: String): Configuration? {
                // Watch carefully for these hard-coded values
                // Now we assume that issuer(indy trustee) is the first created node from SomeNodes
                return if (name == "Trustee") {
                    ConfigurationMap(
                        mapOf(
                            "indyuser.walletName" to name,
                            "indyuser.role" to "trustee",
                            "indyuser.did" to "V4SGRU86Z58d6TV7PBUe6f",
                            "indyuser.seed" to "000000000000000000000000Trustee1",
                            "indyuser.genesisFile" to PoolManager.DEFAULT_GENESIS_FILE
                        )
                    )
                } else ConfigurationMap(
                    mapOf(
                        "indyuser.walletName" to name + random.nextLong().absoluteValue,
                        "indyuser.genesisFile" to PoolManager.DEFAULT_GENESIS_FILE
                    )
                )
            }
        }

        net = InternalMockNetwork(
            cordappPackages = listOf("com.luxoft.blockchainlab.corda.hyperledger.indy"),
            networkParameters = testNetworkParameters(maxTransactionSize = 10485760 * 5)
        )
    }

    @After
    fun commonTearDown() {
        try {
            for (party in parties) {
                party.services.cordaService(IndyService::class.java).indyUser.close()
            }

            parties.clear()
        } finally {
            net.stopNodes()
        }
    }
}