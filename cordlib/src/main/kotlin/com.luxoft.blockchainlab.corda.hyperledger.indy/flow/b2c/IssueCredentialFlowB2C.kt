package com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2c

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.hyperledger.indy.CredentialDefinitionId
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC


object IssueCredentialFlowB2C {
    @InitiatingFlow
    @StartableByRPC
    open class Issuer(
            private val identifier: String,
            private val credentialProposal: String,
            private val credentialDefinitionId: CredentialDefinitionId,
            private val proverDid: String /* Did */
    ) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {

        }
    }
}