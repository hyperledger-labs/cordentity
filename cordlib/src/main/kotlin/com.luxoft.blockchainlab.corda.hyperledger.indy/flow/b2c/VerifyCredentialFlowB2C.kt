package com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2c

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.ProofAttribute
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.ProofPredicate
import com.luxoft.blockchainlab.hyperledger.indy.Interval
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.CordaX500Name


object VerifyCredentialFlowB2C {
    @InitiatingFlow
    @StartableByRPC
    open class Verifier(
            private val identifier: String,
            private val attributes: List<ProofAttribute>,
            private val predicates: List<ProofPredicate>,
            private val proverDid: CordaX500Name,
            private val nonRevoked: Interval? = null
    ) : FlowLogic<Boolean>() {

        @Suspendable
        override fun call(): Boolean {
            return true
        }
    }
}