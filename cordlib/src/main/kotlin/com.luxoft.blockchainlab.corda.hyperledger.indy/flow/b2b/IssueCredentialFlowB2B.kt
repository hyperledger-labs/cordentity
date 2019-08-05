package com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2b

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndyCredentialContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndyRevocationRegistryContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.*
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.*
import com.luxoft.blockchainlab.hyperledger.indy.IndyCredentialDefinitionNotFoundException
import com.luxoft.blockchainlab.hyperledger.indy.IndyCredentialMaximumReachedException
import com.luxoft.blockchainlab.hyperledger.indy.models.*
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.unwrap
import java.util.*

/**
 * Flows to issue Indy credentials
 * */
object IssueCredentialFlowB2B {

    /**
     * A flow to issue an Indy credential based on proposal [credentialProposalFiller]
     *
     * @param proverName                the node that can prove this credential
     * @param credentialDefinitionId    id of the credential definition to create new statement (credential)
     * @param revocationRegistryDefinitionId id of the revocation registry definition linked with this credential definition
     * @param credentialProposalFiller  special builder that allows you to specify credential attributes in a convenient way
     *  example: {
     *      attributes["name"] = CredentialValue("Alex")
     *      attributes["age"] = CredentialValue("40")
     *  }
     *
     * @return                          credential id
     *
     * @note Flows starts by Issuer.
     * E.g User initially comes to university where asks for new education credential.
     * When user verification is completed the University runs IssueCredentialFlowB2B to produce required credential.
     * */
    @InitiatingFlow
    @StartableByRPC
    open class Issuer(
        private val proverName: CordaX500Name,
        private val credentialDefinitionId: CredentialDefinitionId,
        private val revocationRegistryDefinitionId: RevocationRegistryDefinitionId?,
        private val credentialProposalFiller: CredentialProposal.() -> Unit
    ) : FlowLogic<String>() {

        @Suspendable
        override fun call(): String {
            val prover: Party = whoIs(proverName)
            val flowSession: FlowSession = initiateFlow(prover)

            try {
                val revocationRegistryDefinition = if (revocationRegistryDefinitionId == null) null
                else getRevocationRegistryDefinitionById(revocationRegistryDefinitionId)

                if (revocationRegistryDefinition != null)
                    if (!revocationRegistryDefinition.state.data.canProduceCredentials())
                        throw IndyCredentialMaximumReachedException(revocationRegistryDefinition.state.data.id)

                // issue credential
                val id = UUID.randomUUID().toString()
                val offer = indyUser().createCredentialOffer(credentialDefinitionId)

                val signers = listOf(ourIdentity.owningKey, prover.owningKey)
                val newCredentialOut =
                    flowSession.sendAndReceive<CredentialRequestInfo>(offer).unwrap { credentialReq ->
                        val credential = indyUser().issueCredentialAndUpdateLedger(
                            credentialReq,
                            offer,
                            revocationRegistryDefinitionId,
                            credentialProposalFiller
                        )

                        IndyCredential(
                            id,
                            credentialReq,
                            credential,
                            indyUser().walletUser.getIdentityDetails().did,
                            listOf(ourIdentity)
                        )
                    }
                val newCredentialCmdType = IndyCredentialContract.Command.Issue()
                val newCredentialCmd = Command(newCredentialCmdType, signers)

                // checking if cred def exists and can produce new credentials
                val originalCredentialDefIn = getCredentialDefinitionById(credentialDefinitionId)
                    ?: throw IndyCredentialDefinitionNotFoundException(
                        credentialDefinitionId,
                        "State doesn't exist in Corda vault"
                    )

                val trxBuilder = if (revocationRegistryDefinition != null) {
                    // consume credential definition
                    val revocationRegistryDefinitionState = revocationRegistryDefinition.state.data.requestNewCredential()
                    val revocationRegistryDefinitionOut = StateAndContract(
                        revocationRegistryDefinitionState,
                        IndyRevocationRegistryContract::class.java.name
                    )
                    val revocationRegistryDefinitionCmdType = IndyRevocationRegistryContract.Command.Issue()
                    val revocationRegistryDefinitionCmd = Command(revocationRegistryDefinitionCmdType, signers)

                    // do stuff
                    TransactionBuilder(whoIsNotary())
                        .addOutputState(newCredentialOut)
                        .addCommand(newCredentialCmd)
                        .addReferenceState(originalCredentialDefIn.referenced())
                        .withItems(
                            revocationRegistryDefinition,
                            revocationRegistryDefinitionOut,
                            revocationRegistryDefinitionCmd
                        )
                } else {
                    TransactionBuilder(whoIsNotary())
                        .addOutputState(newCredentialOut)
                        .addCommand(newCredentialCmd)
                        .addReferenceState(originalCredentialDefIn.referenced())
                }

                trxBuilder.toWireTransaction(serviceHub)
                    .toLedgerTransaction(serviceHub)
                    .verify()

                val selfSignedTx = serviceHub.signInitialTransaction(trxBuilder, ourIdentity.owningKey)

                flowSession.send(selfSignedTx)
                revocationRegistryDefinition?.also {
                    val revocationRegistryDefinition =
                        indyUser().ledgerUser.retrieveRevocationRegistryDefinition(it.state.data.id)
                    val tailsHash = revocationRegistryDefinition!!.value["tailsHash"].toString()
                    flowSession.send(tailsReader().read(TailsRequest(tailsHash)))
                }
                val signedTrx = flowSession.receive<SignedTransaction>().unwrap { it }

                // Notarise and record the transaction in both parties' vaults.
                finalizeTransaction(signedTrx, listOf(flowSession))

                return id
            } catch (ex: Exception) {
                logger.error("Credential has not been issued", ex)
                throw FlowException(ex.message)
            }
        }
    }

    @InitiatedBy(IssueCredentialFlowB2B.Issuer::class)
    open class Prover(private val flowSession: FlowSession) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            try {
                val issuer = flowSession.counterparty.name

                val offer = flowSession.receive<CredentialOffer>().unwrap { offer -> offer }
                //val sessionDid = subFlow(CreatePairwiseFlowB2B.Prover(issuer))

                val credentialRequestInfo = indyUser().createCredentialRequest(indyUser().walletUser.getIdentityDetails().did, offer)

                val signedTransaction = flowSession.sendAndReceive<SignedTransaction>(credentialRequestInfo).unwrap { it }

                /*val signedByMe = if (!signedTransaction.requiredSigningKeys.contains(serviceHub.myInfo.legalIdentitiesAndCerts.first().owningKey)) {
                    serviceHub.addSignature(signedTransaction)
                } else {
                    signedTransaction
                }*/

                val signedByMe = serviceHub.addSignature(signedTransaction)

                val outputs = signedByMe.tx.outputs

                outputs.forEach {
                    val state = it.data

                    when (state) {
                        is IndyCredential -> {
                            require(state.credentialRequestInfo == credentialRequestInfo) { "Received incorrect CredentialRequest" }
                            indyUser().checkLedgerAndReceiveCredential(
                                state.credentialInfo,
                                state.credentialRequestInfo,
                                offer
                            )
                        }
                        is IndyCredentialDefinition -> logger.info("Got indy credential definition")
                        is IndyRevocationRegistryDefinition -> {
                            //TODO: Make secure
                            flowSession.receive<TailsResponse>().unwrap { tailsWriter().write(it) }
                            logger.info("Got indy revocation registry")
                        }
                        else -> throw FlowException("Invalid output state. Only IndyCredential, IndyCredentialDefinition and IndyRevocationRegistryDefinition supported")
                    }
                }

                flowSession.send(signedByMe)

                if (flowSession.counterparty != me())
                    subFlow(ReceiveFinalityFlow(flowSession, signedByMe.id))
            } catch (ex: Exception) {
                logger.error("Credential has not been issued", ex)
                throw FlowException(ex.message)
            }
        }
    }
}
