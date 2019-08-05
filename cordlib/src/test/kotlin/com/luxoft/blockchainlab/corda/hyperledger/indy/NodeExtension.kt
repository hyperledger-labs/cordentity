package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.CreateCredentialDefinitionFlow
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.CreateRevocationRegistryFlow
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.CreateSchemaFlow
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.RevokeCredentialFlow
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2b.IssueCredentialFlowB2B
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2b.VerifyCredentialFlowB2B
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.IndyService
import com.luxoft.blockchainlab.hyperledger.indy.models.*
import net.corda.core.concurrent.CordaFuture
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.FlowStateMachine
import net.corda.core.utilities.getOrThrow
import net.corda.node.internal.StartedNode
import net.corda.node.services.api.StartedNodeServices
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.newContext
import java.time.Duration
import java.util.*
import kotlin.math.absoluteValue

val flowTimeout = Duration.ofMinutes(2)

/**
 * Substitutes [StartedNodeServices.startFlow] method to run mocked Corda flows.
 *
 * Usage:
 *
 *     val did = store.services.startFlow(GetDidFlowB2B.Initiator(name)).resultFuture.get()
 */
internal fun <T> StartedNodeServices.startFlow(net: InternalMockNetwork, logic: FlowLogic<T>): FlowStateMachine<T> {
    val machine = startFlow(logic, newContext()).getOrThrow()

    return object : FlowStateMachine<T> by machine {
        override val resultFuture: CordaFuture<T>
            get() {
                net.runNetwork()
                return machine.resultFuture
            }
    }
}

fun StartedNode<InternalMockNetwork.MockNode>.getParty() = this.info.singleIdentity()

fun StartedNode<InternalMockNetwork.MockNode>.getName() = getParty().name

fun StartedNode<InternalMockNetwork.MockNode>.getPubKey() = getParty().owningKey

fun CordaX500Name.getNodeByName(net: InternalMockNetwork) =
    net.defaultNotaryNode.services.identityService.wellKnownPartyFromX500Name(this)!!

fun StartedNode<InternalMockNetwork.MockNode>.getPartyDid() =
    this.services.cordaService(IndyService::class.java).indyUser.walletUser.getIdentityDetails().did

fun StartedNode<InternalMockNetwork.MockNode>.issueRandomSchema(net: InternalMockNetwork): Schema {
    val rng = Random()
    val name = "schema-${rng.nextInt().absoluteValue}"
    val version = "${rng.nextInt().absoluteValue}.${rng.nextInt().absoluteValue}"
    val attributeCount = rng.nextInt().absoluteValue % 3 + 2
    val attributes = (0 until attributeCount).map { "attribute-${rng.nextInt().absoluteValue}" }

    val schemaFuture = services.startFlow(
        net,
        CreateSchemaFlow.Authority(name, version, attributes)
    ).resultFuture

    return schemaFuture.getOrThrow(flowTimeout)
}

fun StartedNode<InternalMockNetwork.MockNode>.issueCredentialDefinition(schemaId: SchemaId, enableRevocation: Boolean, net: InternalMockNetwork): CredentialDefinition {
    val credDefFuture = services.startFlow(
        net,
        CreateCredentialDefinitionFlow.Authority(schemaId, enableRevocation)
    ).resultFuture

    return credDefFuture.getOrThrow(flowTimeout)
}

fun StartedNode<InternalMockNetwork.MockNode>.issueRevocationRegistry(credentialDefId: CredentialDefinitionId, credentialsLimit: Int, net: InternalMockNetwork): RevocationRegistryInfo {
    val revocationRegistryFuture = services.startFlow(
        net,
        CreateRevocationRegistryFlow.Authority(credentialDefId, credentialsLimit)
    ).resultFuture

    return revocationRegistryFuture.getOrThrow(flowTimeout)
}

fun StartedNode<InternalMockNetwork.MockNode>.issueRandomCredential(
    prover: StartedNode<InternalMockNetwork.MockNode>,
    schemaAttributes: List<String>,
    credentialDefId: CredentialDefinitionId,
    revocationRegistryId: RevocationRegistryDefinitionId?,
    net: InternalMockNetwork
): Pair<String, Map<String, String>> {
    val rng = Random()
    val attributesToValues = mutableMapOf<String, String>()

    val credentialFuture = services.startFlow(
        net,
        IssueCredentialFlowB2B.Issuer(prover.getName(), credentialDefId, revocationRegistryId) {
            schemaAttributes.forEach {
                val value = rng.nextInt().absoluteValue.toString()
                attributes[it] = CredentialValue(value)

                // for test purposes
                attributesToValues[it] = value
            }
        }
    ).resultFuture

    val credId = credentialFuture.getOrThrow(flowTimeout)

    return Pair(credId, attributesToValues)
}

fun StartedNode<InternalMockNetwork.MockNode>.verify(prover: StartedNode<InternalMockNetwork.MockNode>, proofRequest: ProofRequest, net: InternalMockNetwork): Pair<String?, Boolean> {
    val proofCheckResultFuture = services.startFlow(
        net,
        VerifyCredentialFlowB2B.Verifier(prover.getName(), proofRequest)
    ).resultFuture

    return proofCheckResultFuture.getOrThrow(flowTimeout)
}

fun StartedNode<InternalMockNetwork.MockNode>.issueRandomSimilarCredentials(
    provers: List<StartedNode<InternalMockNetwork.MockNode>>,
    enableRevocation: Boolean,
    count: Int,
    maxCredentialsPerRevRegistry: Int,
    net: InternalMockNetwork
): List<CordaTestBase.CredentialAndMetadata> {
    val schema = issueRandomSchema(net)
    val credDef = issueCredentialDefinition(schema.getSchemaIdObject(), enableRevocation, net)
    val revRegInfo = if (enableRevocation)
        issueRevocationRegistry(credDef.getCredentialDefinitionIdObject(), maxCredentialsPerRevRegistry, net)
    else
        null

    return provers.map { prover ->
        (0 until count).map {
            val idAndValues = issueRandomCredential(
                prover,
                schema.attributeNames,
                credDef.getCredentialDefinitionIdObject(),
                revRegInfo?.definition?.getRevocationRegistryIdObject(),
                net
            )

            CordaTestBase.CredentialAndMetadata(schema, credDef, revRegInfo, idAndValues, prover)
        }
    }.flatten()
}

fun StartedNode<InternalMockNetwork.MockNode>.issueRandomDifferentCredentials(
    provers: List<StartedNode<InternalMockNetwork.MockNode>>,
    enableRevocation: Boolean,
    count: Int,
    maxCredentialsPerRevRegistry: Int,
    net: InternalMockNetwork
) = provers.map { prover ->
    (0 until count).map {
        val schema = issueRandomSchema(net)
        val credDef = issueCredentialDefinition(schema.getSchemaIdObject(), enableRevocation, net)
        val revRegInfo = if (enableRevocation)
            issueRevocationRegistry(credDef.getCredentialDefinitionIdObject(), maxCredentialsPerRevRegistry, net)
        else
            null

        val idAndValues = issueRandomCredential(
            prover,
            schema.attributeNames,
            credDef.getCredentialDefinitionIdObject(),
            revRegInfo?.definition?.getRevocationRegistryIdObject(),
            net
        )

        CordaTestBase.CredentialAndMetadata(schema, credDef, revRegInfo, idAndValues, prover)
    }
}.flatten()

fun StartedNode<InternalMockNetwork.MockNode>.revoke(id: String, net: InternalMockNetwork) {
    val revokeFuture = services.startFlow(
        net,
        RevokeCredentialFlow.Issuer(id)
    ).resultFuture

    revokeFuture.getOrThrow(flowTimeout)
}
