package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.hyperledger.indy.models.*
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC


/**
 * Creates schema, credential definition and revocation registry
 */
object CreateCredentialMetadataFlow {

    /**
     * @param schemaName [String]
     * @param schemaVersion [String]
     * @param schemaAttributes [List] of [String]
     * @param credentialLimit [Int]
     */
    @InitiatingFlow
    @StartableByRPC
    class Authority(
        private val schemaName: String,
        private val schemaVersion: String,
        private val schemaAttributes: List<String>,
        private val credentialLimit: Int
    ): FlowLogic<Triple<Schema, CredentialDefinition, RevocationRegistryInfo>>() {
        @Suspendable
        override fun call(): Triple<Schema, CredentialDefinition, RevocationRegistryInfo> {
            val schema = subFlow(CreateSchemaFlow.Authority(schemaName, schemaVersion, schemaAttributes))
            val credentialDefinition = subFlow(CreateCredentialDefinitionFlow.Authority(schema.getSchemaIdObject(), true))
            val revocationRegistryInfo = subFlow(CreateRevocationRegistryFlow.Authority(credentialDefinition.getCredentialDefinitionIdObject(), credentialLimit))

            return Triple(schema, credentialDefinition, revocationRegistryInfo)
        }
    }
}
