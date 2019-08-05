package com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2c

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndyCredentialContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndyRevocationRegistryContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyCredential
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.getCredentialDefinitionById
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.getRevocationRegistryDefinitionById
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.finalizeTransaction
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.indyUser
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.whoIsNotary
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.awaitFiber
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.connectionService
import com.luxoft.blockchainlab.hyperledger.indy.IndyCredentialDefinitionNotFoundException
import com.luxoft.blockchainlab.hyperledger.indy.IndyCredentialMaximumReachedException
import com.luxoft.blockchainlab.hyperledger.indy.models.CredentialDefinitionId
import com.luxoft.blockchainlab.hyperledger.indy.models.CredentialProposal
import com.luxoft.blockchainlab.hyperledger.indy.models.RevocationRegistryDefinitionId
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.transactions.TransactionBuilder


object IssueCredentialFlowB2C {
    @InitiatingFlow
    @StartableByRPC
    open class Issuer(
        private val identifier: String,
        private val credentialDefinitionId: CredentialDefinitionId,
        private val revocationRegistryDefinitionId: RevocationRegistryDefinitionId?,
        private val indyPartyDID: String,
        private val credentialProposalFiller: CredentialProposal.() -> Unit
    ) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            try {
                val revocationRegistryDefinition = if (revocationRegistryDefinitionId == null) null
                else getRevocationRegistryDefinitionById(revocationRegistryDefinitionId)

                if (revocationRegistryDefinition != null)
                    if (!revocationRegistryDefinition.state.data.canProduceCredentials())
                        throw IndyCredentialMaximumReachedException(revocationRegistryDefinition.state.data.id)

                // issue credential
                val offer = indyUser().createCredentialOffer(credentialDefinitionId)

                connectionService().sendCredentialOffer(offer, indyPartyDID)

                val credentialRequest = connectionService().receiveCredentialRequest(indyPartyDID).awaitFiber()

                val credential = indyUser().issueCredentialAndUpdateLedger(
                    credentialRequest,
                    offer,
                    revocationRegistryDefinitionId,
                    credentialProposalFiller
                )

                connectionService().sendCredential(credential, indyPartyDID)

                val credentialOut = IndyCredential(
                    identifier,
                    credentialRequest,
                    credential,
                    indyUser().walletUser.getIdentityDetails().did,
                    listOf(ourIdentity)
                )

                val signers = listOf(ourIdentity.owningKey)

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
                    val revocationRegistryDefinitionState =
                        revocationRegistryDefinition.state.data.requestNewCredential()
                    val revocationRegistryDefinitionOut = StateAndContract(
                        revocationRegistryDefinitionState,
                        IndyRevocationRegistryContract::class.java.name
                    )
                    val revocationRegistryDefinitionCmdType = IndyRevocationRegistryContract.Command.Issue()
                    val revocationRegistryDefinitionCmd = Command(revocationRegistryDefinitionCmdType, signers)

                    // do stuff
                    TransactionBuilder(whoIsNotary())
                        .addOutputState(credentialOut)
                        .addCommand(newCredentialCmd)
                        .addReferenceState(originalCredentialDefIn.referenced())
                        .withItems(
                            revocationRegistryDefinition,
                            revocationRegistryDefinitionOut,
                            revocationRegistryDefinitionCmd
                        )
                } else {
                    TransactionBuilder(whoIsNotary())
                        .addOutputState(credentialOut)
                        .addCommand(newCredentialCmd)
                        .addReferenceState(originalCredentialDefIn.referenced())
                }

                trxBuilder.toWireTransaction(serviceHub)
                    .toLedgerTransaction(serviceHub)
                    .verify()

                val selfSignedTx = serviceHub.signInitialTransaction(trxBuilder, ourIdentity.owningKey)

                // Notarise and record the transaction in both parties' vaults.
                finalizeTransaction(selfSignedTx)
            } catch (ex: Exception) {
                logger.error("Credential has not been issued", ex)
                throw FlowException(ex.message)
            }
        }
    }
}
