package com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2b

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.indyUser
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.whoIs
import com.luxoft.blockchainlab.hyperledger.indy.models.IdentityDetails
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.utilities.unwrap


/**
 * Flows to request the DID (Decentralized ID) of a Corda party
 **/
object GetDidFlowB2B {

    /**
     * A flow to request the DID (Decentralized ID) of another Corda party [authority]
     *
     * @returns DID of the [authority] node
     **/
    @InitiatingFlow
    @StartableByRPC
    open class Initiator(private val authority: CordaX500Name) : FlowLogic<String>() {

        @Suspendable
        override fun call(): String {
            try {
                val otherSide: Party = whoIs(authority)
                val flowSession: FlowSession = initiateFlow(otherSide)

                return flowSession.receive<IdentityDetails>().unwrap { it.did }

            } catch (ex: Exception) {
                logger.error("", ex)
                throw FlowException(ex.message)
            }
        }
    }

    @InitiatedBy(GetDidFlowB2B.Initiator::class)
    open class Authority(private val flowSession: FlowSession) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            try {
                val identityDetails = indyUser().getIdentity()

                flowSession.send(identityDetails)
            } catch (e: Exception) {
                logger.error("", e)
                throw FlowException(e.message)
            }
        }

    }
}