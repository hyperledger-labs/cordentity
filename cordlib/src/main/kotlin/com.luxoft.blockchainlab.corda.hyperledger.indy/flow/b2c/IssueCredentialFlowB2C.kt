package com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2c

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndyCredentialContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndyCredentialDefinitionContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyCredential
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.getCredentialDefinitionById
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.indyUser
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.whoIsNotary
import com.luxoft.blockchainlab.hyperledger.indy.CredentialDefinitionId
import com.luxoft.blockchainlab.hyperledger.indy.IndyCredentialDefinitionNotFoundException
import com.luxoft.blockchainlab.hyperledger.indy.IndyCredentialMaximumReachedException
import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.flows.*
import net.corda.core.transactions.TransactionBuilder


object IssueCredentialFlowB2C {
    @InitiatingFlow
    @StartableByRPC
    open class Issuer(
            private val identifier: String,
            private val credentialProposal: String,
            private val credentialDefinitionId: CredentialDefinitionId
    ) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            try {
                // checking if cred def exists and can produce new credentials
                val originalCredentialDefIn = getCredentialDefinitionById(credentialDefinitionId)
                        ?: throw IndyCredentialDefinitionNotFoundException(
                                credentialDefinitionId.toString(),
                                "State doesn't exist in Corda vault"
                        )
                val originalCredentialDef = originalCredentialDefIn.state.data

                if (!originalCredentialDef.canProduceCredentials())
                    throw IndyCredentialMaximumReachedException(
                            originalCredentialDef.credentialDefinitionId.getRevocationRegistryDefinitionId(
                                    IndyUser.REVOCATION_TAG
                            ).toString()
                    )

                // issue credential
                val offer = indyUser().createCredentialOffer(credentialDefinitionId)

                connectionService().sendCredentialOffer(offer)

                val credentialRequest = connectionService().receiveCredentialRequest()

                val credential = indyUser().issueCredential(
                        credentialRequest,
                        credentialProposal,
                        offer
                )

                connectionService().sendCredential(credential)

                val credentialOut = IndyCredential(
                        identifier,
                        credentialRequest,
                        credential,
                        indyUser().did,
                        listOf(ourIdentity)
                )
                val newCredentialOut = StateAndContract(credentialOut, IndyCredentialContract::class.java.name)

                val signers = listOf(ourIdentity.owningKey)

                val newCredentialCmdType = IndyCredentialContract.Command.Issue()
                val newCredentialCmd = Command(newCredentialCmdType, signers)

                // consume credential definition
                val credentialDefinition = originalCredentialDef.requestNewCredential()
                val credentialDefinitionOut =
                        StateAndContract(credentialDefinition, IndyCredentialDefinitionContract::class.java.name)
                val credentialDefinitionCmdType = IndyCredentialDefinitionContract.Command.Consume()
                val credentialDefinitionCmd = Command(credentialDefinitionCmdType, signers)

                // do stuff
                val trxBuilder = TransactionBuilder(whoIsNotary()).withItems(
                        originalCredentialDefIn,
                        newCredentialOut,
                        newCredentialCmd,
                        credentialDefinitionOut,
                        credentialDefinitionCmd
                )

                trxBuilder.toWireTransaction(serviceHub)
                        .toLedgerTransaction(serviceHub)
                        .verify()

                val selfSignedTx = serviceHub.signInitialTransaction(trxBuilder, ourIdentity.owningKey)

                // Notarise and record the transaction in both parties' vaults.
                subFlow(FinalityFlow(selfSignedTx))
            } catch (ex: Exception) {
                logger.error("Credential has not been issued", ex)
                throw FlowException(ex.message)
            }
        }
    }
}