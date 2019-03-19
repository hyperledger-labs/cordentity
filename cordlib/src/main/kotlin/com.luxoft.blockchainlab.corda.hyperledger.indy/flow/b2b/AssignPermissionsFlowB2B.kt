package com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2b

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.indyUser
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.whoIs
import com.luxoft.blockchainlab.hyperledger.indy.models.IdentityDetails
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import net.corda.core.utilities.unwrap

/**
 * Flows to change permissions of another Corda party [authority]
 * */
object AssignPermissionsFlowB2B {

    /**
     * @param did          Target DID as base58-encoded string for 16 or 32 bit DID value.
     * @param verkey       Target identity verification key as base58-encoded string.
     * @param alias        NYM's alias.
     * @param role         Role of a user NYM record: { null (common USER), TRUSTEE, STEWARD, TRUST_ANCHOR, empty string (reset role) }
     * */
    @CordaSerializable
    // TODO: make private
    data class IndyPermissionsRequest(
        val did: String = "",
        val verkey: String = "",
        val alias: String?,
        val role: String?
    )

    /**
     * A flow to change permissions of another Corda party [authority]
     *
     * @param authority    Corda party whose permissions are changing
     * @param alias        NYM's alias.
     * @param role         Role of a user NYM record: { null (common USER), TRUSTEE, STEWARD, TRUST_ANCHOR, empty string (reset role) }
     * */
    @InitiatingFlow
    @StartableByRPC
    open class Issuer(
        private val alias: String? = null,
        private val role: String? = null,
        private val authority: CordaX500Name
    ) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            try {
                val otherSide: Party = whoIs(authority)
                val flowSession: FlowSession = initiateFlow(otherSide)

                // FIXME: parameters `role` and `alias` are mixed up
                flowSession.send(IndyPermissionsRequest(indyUser().did, indyUser().verkey, role, alias))

            } catch (t: Throwable) {
                logger.error("", t)
                throw FlowException(t.message)
            }
        }
    }

    @InitiatedBy(AssignPermissionsFlowB2B.Issuer::class)
    open class Authority(private val flowSession: FlowSession) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            try {
                flowSession.receive(AssignPermissionsFlowB2B.IndyPermissionsRequest::class.java).unwrap { indyPermissions ->
                    // FIXME: parameters `role` and `alias` are mixed up
                    indyUser().setPermissionsFor(
                        IdentityDetails(
                            indyPermissions.did,
                            indyPermissions.verkey,
                            indyPermissions.role,
                            indyPermissions.alias
                        )
                    )
                }

            } catch (ex: Exception) {
                logger.error("", ex)
                throw FlowException(ex.message)
            }
        }

    }

}