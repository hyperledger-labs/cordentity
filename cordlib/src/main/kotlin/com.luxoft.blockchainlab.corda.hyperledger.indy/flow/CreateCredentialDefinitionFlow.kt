package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndyCredentialDefinitionContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndySchemaContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyCredentialDefinition
import com.luxoft.blockchainlab.hyperledger.indy.*
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.flows.*
import net.corda.core.transactions.TransactionBuilder


/**
 * Flow to create a credential definition and revocation registry for a schema
 * */
object CreateCredentialDefinitionFlow {

    /**
     * @param schemaId             Id of target schema
     * @param credentialsLimit     Maximum number of possible credentials issued per definition
     *
     * @returns                    credential definition persistent id
     * */
    @InitiatingFlow
    @StartableByRPC
    class Authority(private val schemaId: SchemaId, private val credentialsLimit: Int = 100) :
        FlowLogic<CredentialDefinitionId>() {

        @Suspendable
        override fun call(): CredentialDefinitionId {
            try {
                checkNoCredentialDefinitionOnCorda()
                checkNoCredentialDefinitionOnIndy()

                // create indy stuff
                val credentialDefinitionObj = indyUser().createCredentialDefinition(schemaId, true)
                val credentialDefinitionId = credentialDefinitionObj.getCredentialDefinitionId()
                indyUser().createRevocationRegistry(credentialDefinitionId, credentialsLimit)

                val signers = listOf(ourIdentity.owningKey)
                // create new credential definition state
                val credentialDefinition = IndyCredentialDefinition(
                    schemaId,
                    credentialDefinitionId,
                    credentialsLimit,
                    listOf(ourIdentity)
                )
                val credentialDefinitionOut =
                    StateAndContract(credentialDefinition, IndyCredentialDefinitionContract::class.java.name)
                val credentialDefinitionCmdType = IndyCredentialDefinitionContract.Command.Create()
                val credentialDefinitionCmd = Command(credentialDefinitionCmdType, signers)

                // consume old schema state
                val schemaIn = getSchemaById(schemaId)
                    ?: throw IndySchemaNotFoundException(
                        schemaId.toString(),
                        "Corda does't have proper schema in vault"
                    )

                val schemaOut = StateAndContract(schemaIn.state.data, IndySchemaContract::class.java.name)
                val schemaCmdType = IndySchemaContract.Command.Consume()
                val schemaCmd = Command(schemaCmdType, signers)

                // do stuff
                val trxBuilder = TransactionBuilder(whoIsNotary()).withItems(
                    schemaIn,
                    credentialDefinitionOut,
                    credentialDefinitionCmd,
                    schemaOut,
                    schemaCmd
                )

                trxBuilder.toWireTransaction(serviceHub)
                    .toLedgerTransaction(serviceHub)
                    .verify()

                val selfSignedTx = serviceHub.signInitialTransaction(trxBuilder, ourIdentity.owningKey)

                subFlow(FinalityFlow(selfSignedTx))

                return credentialDefinitionId

            } catch (t: Throwable) {
                logger.error("New credential definition has been failed", t)
                throw FlowException(t.message)
            }
        }

        private fun checkNoCredentialDefinitionOnCorda() {
            getSchemaById(schemaId)
                ?: throw IndySchemaNotFoundException(schemaId.toString(), "Corda does't have proper states")

            if (getCredentialDefinitionBySchemaId(schemaId) != null) {
                throw IndyCredentialDefinitionAlreadyExistsException(
                    schemaId.toString(),
                    "Credential definition already exist on Corda ledger"
                )
            }
        }

        private fun checkNoCredentialDefinitionOnIndy() {
            if (indyUser().isCredentialDefinitionExist(schemaId))
                throw IndyCredentialDefinitionAlreadyExistsException(
                    schemaId.toString(),
                    "Credential definition already exist on Indy ledger"
                )
        }
    }
}