package com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2c

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.Connection
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndyCredentialContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyCredentialProof
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.ProofAttribute
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.ProofPredicate
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.indyUser
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.whoIsNotary
import com.luxoft.blockchainlab.hyperledger.indy.CredentialFieldReference
import com.luxoft.blockchainlab.hyperledger.indy.CredentialPredicate
import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import com.luxoft.blockchainlab.hyperledger.indy.Interval
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.flows.*
import net.corda.core.transactions.TransactionBuilder


object VerifyCredentialFlowB2C {
    @InitiatingFlow
    @StartableByRPC
    open class Verifier(
            private val identifier: String,
            private val attributes: List<ProofAttribute>,
            private val predicates: List<ProofPredicate>,
            private val nonRevoked: Interval? = null
    ) : FlowLogic<Boolean>() {

        @Suspendable
        override fun call(): Boolean {

            try {
                val fieldRefAttr = attributes.map {
                    CredentialFieldReference(
                            it.field,
                            it.schemaId.toString(),
                            it.credentialDefinitionId.toString()
                    )
                }

                val fieldRefPred = predicates.map {
                    val fieldRef = CredentialFieldReference(
                            it.field,
                            it.schemaId.toString(),
                            it.credentialDefinitionId.toString()
                    )
                    CredentialPredicate(fieldRef, it.value)
                } //com.fasterxml.jackson.databind.ObjectMapper@548ccc41

                val proofRequest = IndyUser.createProofRequest(
                        version = "0.1",
                        name = "proof_req_0.1",
                        attributes = fieldRefAttr,
                        predicates = fieldRefPred,
                        nonRevoked = nonRevoked
                )

                connectionService().sendProofRequest(proofRequest)

                val proof = connectionService().receiveProof()

                val usedData = indyUser().getDataUsedInProof(proofRequest, proof)
                val credentialProofOut =
                        IndyCredentialProof(identifier, proofRequest, proof, usedData, listOf(ourIdentity))

                if (!indyUser().verifyProof(
                                credentialProofOut.proofReq,
                                proof,
                                usedData
                        )
                ) throw FlowException("Proof verification failed")

                val verifyCredentialOut = StateAndContract(credentialProofOut, IndyCredentialContract::class.java.name)

                val expectedAttrs = attributes
                        .filter { it.value.isNotEmpty() }
                        .associateBy({ it.field }, { it.value })
                        .map { IndyCredentialContract.ExpectedAttr(it.key, it.value) }

                val verifyCredentialCmdType = IndyCredentialContract.Command.Verify(expectedAttrs)
                val verifyCredentialCmd =
                        Command(verifyCredentialCmdType, listOf(ourIdentity.owningKey))

                val trxBuilder = TransactionBuilder(whoIsNotary())
                        .withItems(verifyCredentialOut, verifyCredentialCmd)

                trxBuilder.toWireTransaction(serviceHub)
                        .toLedgerTransaction(serviceHub)
                        .verify()

                val selfSignedTx = serviceHub.signInitialTransaction(trxBuilder)

                // Notarise and record the transaction in both parties' vaults.
                subFlow(FinalityFlow(selfSignedTx))

                return true

            } catch (e: Exception) {
                logger.error("", e)
                return false
            }
        }
    }
}