package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndyCredentialDefinitionContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyCredentialDefinition
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.getSchemaById
import com.luxoft.blockchainlab.hyperledger.indy.IndySchemaNotFoundException
import com.luxoft.blockchainlab.hyperledger.indy.models.CredentialDefinition
import com.luxoft.blockchainlab.hyperledger.indy.models.SchemaId
import net.corda.core.contracts.Command
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.transactions.TransactionBuilder


/**
 * Flow to create a credential definition for a schema
 * */
object CreateCredentialDefinitionFlow {

    /**
     * @param schemaId             Id of target schema
     * @param enableRevocation [Boolean]
     *
     * @returns                    credential definition persistent id
     * */
    @InitiatingFlow
    @StartableByRPC
    class Authority(
        private val schemaId: SchemaId,
        private val enableRevocation: Boolean
    ) : FlowLogic<CredentialDefinition>() {

        @Suspendable
        override fun call(): CredentialDefinition {
            try {
                // create indy stuff
                val credentialDefinitionObj =
                    indyUser().createCredentialDefinitionAndStoreOnLedger(schemaId, enableRevocation)
                val credentialDefinitionId = credentialDefinitionObj.getCredentialDefinitionIdObject()

                val signers = listOf(ourIdentity.owningKey)

                // create new credential definition state
                val credentialDefinition = IndyCredentialDefinition(
                    credentialDefinitionId,
                    schemaId,
                    enableRevocation,
                    listOf(ourIdentity)
                )
                val credentialDefinitionCmdType = IndyCredentialDefinitionContract.Command.Create()
                val credentialDefinitionCmd = Command(credentialDefinitionCmdType, signers)

                val schemaIn = getSchemaById(schemaId)
                    ?: throw IndySchemaNotFoundException(schemaId, "Corda does't have proper schema in vault")

                // do stuff
                val trxBuilder = TransactionBuilder(whoIsNotary())
                    .addOutputState(credentialDefinition)
                    .addCommand(credentialDefinitionCmd)
                    .addReferenceState(schemaIn.referenced())

                trxBuilder.toWireTransaction(serviceHub)
                    .toLedgerTransaction(serviceHub)
                    .verify()

                val selfSignedTx = serviceHub.signInitialTransaction(trxBuilder, ourIdentity.owningKey)

                finalizeTransaction(selfSignedTx)

                return credentialDefinitionObj

            } catch (t: Throwable) {
                logger.error("New credential definition has been failed", t)
                throw FlowException(t.message)
            }
        }
    }
}
