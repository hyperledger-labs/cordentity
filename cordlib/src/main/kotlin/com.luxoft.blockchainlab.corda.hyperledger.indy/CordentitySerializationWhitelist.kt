package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.*
import net.corda.core.serialization.SerializationWhitelist


class CordentitySerializationWhitelist : SerializationWhitelist {
    override val whitelist: List<Class<*>> = listOf(
            Interval::class.java,
            CredentialFieldReference::class.java,
            CredentialPredicate::class.java,
            CredentialOffer::class.java,
            KeyCorrectnessProof::class.java,
            Credential::class.java,
            CredentialValue::class.java,
            CredentialInfo::class.java,
            CredentialRequestInfo::class.java,
            CredentialRequest::class.java,
            CredentialRequestMetadata::class.java,
            ProofRequestCredentials::class.java,
            CredentialReferenceInfo::class.java,
            CredentialReference::class.java,
            RequestedCredentials::class.java,
            RequestedAttributeInfo::class.java,
            RequestedPredicateInfo::class.java,
            ProofRequest::class.java,
            CredentialAttributeReference::class.java,
            CredentialPredicateReference::class.java,
            ParsedProof::class.java,
            ProofInfo::class.java,
            ProofIdentifier::class.java,
            Proof::class.java,
            RevealedAttributeReference::class.java,
            RevealedPredicateReference::class.java,
            RequestedProof::class.java,
            ProofDetails::class.java,
            Schema::class.java,
            CredentialDefinition::class.java,
            CredentialPubKeys::class.java,
            RevocationRegistryConfig::class.java,
            RevocationRegistryInfo::class.java,
            RevocationRegistryDefinition::class.java,
            RevocationRegistryEntry::class.java,
            RevocationState::class.java,
            DataUsedInProofJson::class.java,
            IdentityDetails::class.java,
            RevocationRegistryDefinitionId::class.java,
            CredentialDefinitionId::class.java,
            SchemaId::class.java
    )
}