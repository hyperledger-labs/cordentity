package com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2b

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.indyUser
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.whoIs
import com.luxoft.blockchainlab.hyperledger.indy.models.IdentityDetails
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils.anyToJSON
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.utilities.unwrap

/**
 * Utility flows to initiate a bi-directional connection with a Corda node
 * */
object CreatePairwiseFlowB2B {

    /**
     * An utility flow to initiate a bi-directional connection with a Corda node
     *
     * @param authority Corda node to connect to
     * @returns         session DID
     * */
    @InitiatingFlow
    open class Prover(private val authority: CordaX500Name) : FlowLogic<String>() {

        @Suspendable
        override fun call(): String {
            try {
                val otherSide: Party = whoIs(authority)
                val flowSession: FlowSession = initiateFlow(otherSide)

                val sessionDid = flowSession.receive<IdentityDetails>().unwrap {
                    indyUser().createSessionDid(it)
                }

                val identityDetails = indyUser().getIdentity(sessionDid)

                flowSession.send(identityDetails)
                return sessionDid

            } catch (ex: Exception) {
                logger.error("Pairwise has not been created", ex)
                throw FlowException(ex.message)
            }
        }
    }


    @InitiatedBy(CreatePairwiseFlowB2B.Prover::class)
    open class Issuer(private val flowSession: FlowSession) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            try {
                val myIdentityRecord = indyUser().getIdentity()

                flowSession.sendAndReceive<IdentityDetails>(myIdentityRecord).unwrap {
                    indyUser().addKnownIdentities(it)
                }
            } catch (t: Throwable) {
                logger.error("", t)
                throw FlowException(t.message)
            }
        }

    }
}