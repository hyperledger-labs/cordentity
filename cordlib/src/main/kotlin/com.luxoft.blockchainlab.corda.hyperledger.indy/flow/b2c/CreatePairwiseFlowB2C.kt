package com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2c

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow

// TODO: this flow should create connection somehow
object CreatePairwiseFlowB2C {
    @InitiatingFlow
    open class Prover(private val authority: String /* Did */) : FlowLogic<String>() {
        @Suspendable
        override fun call(): String {
            return "" // TODO: return sessionDid
        }
    }
}