package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.hyperledger.indy.IdentityDetails
import com.luxoft.blockchainlab.hyperledger.indy.roles.getIdentity
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.utilities.unwrap

/**
 * Utility flows to initiate a bi-directional connection with a Corda node
 * */
object CreatePairwiseFlow {

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

                val sessionDid = flowSession.receive<String>().unwrap { theirIdentityRecord ->
                    val identityDetails = SerializationUtils.jSONToAny<IdentityDetails>(theirIdentityRecord)

                    indyUser().createSessionDid(identityDetails)
                }

                flowSession.send(indyUser().getIdentity(sessionDid).getIdentityRecord())
                return sessionDid

            } catch (ex: Exception) {
                logger.error("Pairwise has not been created", ex)
                throw FlowException(ex.message)
            }
        }
    }


    @InitiatedBy(CreatePairwiseFlow.Prover::class)
    open class Issuer(private val flowSession: FlowSession) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            try {
                val myIdentityRecord = indyUser().getIdentity().getIdentityRecord()

                flowSession.sendAndReceive<String>(myIdentityRecord).unwrap { theirIdentityRecord ->
                    val identityDetails = SerializationUtils.jSONToAny<IdentityDetails>(theirIdentityRecord)

                    indyUser().addKnownIdentities(identityDetails)
                }
            } catch (t: Throwable) {
                logger.error("", t)
                throw FlowException(t.message)
            }
        }

    }
}