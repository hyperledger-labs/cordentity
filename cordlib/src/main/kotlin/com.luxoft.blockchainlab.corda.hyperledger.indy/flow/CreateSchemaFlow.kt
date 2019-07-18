package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndySchemaContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndySchema
import com.luxoft.blockchainlab.hyperledger.indy.IndySchemaAlreadyExistsException
import com.luxoft.blockchainlab.hyperledger.indy.models.Schema
import com.luxoft.blockchainlab.hyperledger.indy.models.SchemaId
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.transactions.TransactionBuilder

/**
 * Flows to create an Indy scheme
 * */
object CreateSchemaFlow {

    /**
     * A flow to create an Indy scheme
     *
     * @param schemaName        name of the new schema
     * @param schemaVersion     version of the schema
     * @param schemaAttributes  a list of attribute names
     * @returns                 Schema ID
     * */
    @InitiatingFlow
    @StartableByRPC
    class Authority(
        private val schemaName: String,
        private val schemaVersion: String,
        private val schemaAttributes: List<String>
    ) : FlowLogic<Schema>() {

        @Suspendable
        override fun call(): Schema {
            try {
                // check if schema already exists
                val schemaId = SchemaId(indyUser().walletUser.getIdentityDetails().did, schemaName, schemaVersion)
                if (indyUser().ledgerUser.schemaExists(schemaId))
                    throw IndySchemaAlreadyExistsException(schemaName, schemaVersion)

                // create schema
                val schemaObj = indyUser().createSchemaAndStoreOnLedger(schemaName, schemaVersion, schemaAttributes)
                val schema = IndySchema(schemaObj.getSchemaIdObject(), listOf(ourIdentity))
                val schemaOut = StateAndContract(schema, IndySchemaContract::class.java.name)

                val newSchemaCmdType = IndySchemaContract.Command.Create()
                val newSchemaCmd = Command(newSchemaCmdType, listOf(ourIdentity.owningKey))

                val trxBuilder = TransactionBuilder(whoIsNotary())
                    .withItems(schemaOut, newSchemaCmd)

                trxBuilder.toWireTransaction(serviceHub)
                    .toLedgerTransaction(serviceHub)
                    .verify()

                val selfSignedTx = serviceHub.signInitialTransaction(trxBuilder, ourIdentity.owningKey)

                finalizeTransaction(selfSignedTx)

                return schemaObj

            } catch (t: Throwable) {
                logger.error("New schema creating has been failed", t)
                throw FlowException(t.message)
            }
        }
    }
}
