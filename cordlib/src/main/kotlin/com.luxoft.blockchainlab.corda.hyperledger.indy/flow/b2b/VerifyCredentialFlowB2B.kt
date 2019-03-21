package com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2b

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndyCredentialContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyCredentialProof
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.*
import com.luxoft.blockchainlab.hyperledger.indy.*
import com.luxoft.blockchainlab.hyperledger.indy.models.*
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.unwrap

/**
 * Flows to verify predicates on attributes
 * */
object VerifyCredentialFlowB2B {



    /**
     * A flow to verify a set of predicates [predicates] on a set of attributes [attributes]
     *
     * @param identifier        new unique ID for the new proof to allow searching Proofs by [identifier]
     * @param attributes        unordered list of attributes that are needed for verification
     * @param predicates        unordered list of predicates that will be checked
     * @param proverName        node that will prove the credentials
     *
     * @param nonRevoked        <optional> time interval to verify non-revocation
     *                          if not specified then revocation is not verified
     *
     * @returns TRUE if verification succeeds
     *
     * TODO: make it return false in case of failed verification
     * */
    @InitiatingFlow
    @StartableByRPC
    open class Verifier(
            private val identifier: String,
            private val attributes: List<ProofAttribute>,
            private val predicates: List<ProofPredicate>,
            private val proverName: CordaX500Name,
            private val nonRevoked: Interval = Interval.now()
    ) : FlowLogic<Boolean>() {

        @Suspendable
        override fun call(): Boolean {
            try {
                val prover: Party = whoIs(proverName)
                val flowSession: FlowSession = initiateFlow(prover)

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
                }

                val proofRequest = IndyUser.createProofRequest(
                    version = "0.1",
                    name = "proof_req_0.1",
                    attributes = fieldRefAttr,
                    predicates = fieldRefPred,
                    nonRevoked = nonRevoked
                )

                val verifyCredentialOut = flowSession.sendAndReceive<ProofInfo>(proofRequest).unwrap { proof ->
                    val usedData = indyUser().getDataUsedInProof(proofRequest, proof)
                    val credentialProofOut =
                        IndyCredentialProof(identifier, proofRequest, proof, usedData, listOf(ourIdentity, prover))

                    if (!indyUser().verifyProof(
                            credentialProofOut.proofReq,
                            proof,
                            usedData
                        )
                    ) throw FlowException("Proof verification failed")

                    StateAndContract(credentialProofOut, IndyCredentialContract::class.java.name)
                }

                val expectedAttrs = attributes
                    .filter { it.value.isNotEmpty() }
                    .associateBy({ it.field }, { it.value })
                    .map { IndyCredentialContract.ExpectedAttr(it.key, it.value) }

                val verifyCredentialCmdType = IndyCredentialContract.Command.Verify(expectedAttrs)
                val verifyCredentialCmd =
                    Command(verifyCredentialCmdType, listOf(ourIdentity.owningKey, prover.owningKey))

                val trxBuilder = TransactionBuilder(whoIsNotary())
                    .withItems(verifyCredentialOut, verifyCredentialCmd)

                trxBuilder.toWireTransaction(serviceHub)
                    .toLedgerTransaction(serviceHub)
                    .verify()

                val selfSignedTx = serviceHub.signInitialTransaction(trxBuilder)
                val signedTrx = subFlow(CollectSignaturesFlow(selfSignedTx, listOf(flowSession)))

                // Notarise and record the transaction in both parties' vaults.
                subFlow(FinalityFlow(signedTrx))

                return true

            } catch (e: Exception) {
                logger.error("", e)
                return false
            }
        }
    }

    @InitiatedBy(VerifyCredentialFlowB2B.Verifier::class)
    open class Prover(private val flowSession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            try {
                val indyProofRequest = flowSession.receive<ProofRequest>().unwrap { it }
                flowSession.send(indyUser().createProof(indyProofRequest))

                val flow = object : SignTransactionFlow(flowSession) {
                    // TODO: Add some checks here.
                    override fun checkTransaction(stx: SignedTransaction) = Unit
                }

                subFlow(flow)

            } catch (e: Exception) {
                logger.error("", e)
                throw FlowException(e.message)
            }
        }
    }
}