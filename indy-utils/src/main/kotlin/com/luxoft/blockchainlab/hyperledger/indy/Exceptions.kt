package com.luxoft.blockchainlab.hyperledger.indy

import java.lang.RuntimeException


class IndyCredentialDefinitionAlreadyExistsException(schemaId: SchemaId, msg: String) :
        IllegalArgumentException("Credential definition for schema: $schemaId is already exists")

class IndyCredentialMaximumReachedException(revRegId: RevocationRegistryDefinitionId) :
        IllegalArgumentException("Revocation registry with id: $revRegId cannot hold more credentials")

class IndySchemaAlreadyExistsException(name: String, version: String) :
        IllegalArgumentException("Schema with name $name and version $version already exists")

class IndySchemaNotFoundException(id: SchemaId, msg: String) :
        IllegalArgumentException("There is no schema with id: $id. $msg")

class IndyRevRegNotFoundException(id: RevocationRegistryDefinitionId, msg: String) :
        IllegalArgumentException("There is no revocation registry with id: $id. $msg")

class IndyRevDeltaNotFoundException(id: RevocationRegistryDefinitionId, msg: String) :
        IllegalArgumentException("Revocation registry delta $id for definition doesn't exist in ledger. $msg")

class IndyCredentialDefinitionNotFoundException(id: CredentialDefinitionId, msg: String) :
        IllegalArgumentException("There is no credential definition with id: $id. $msg")

class GenesisPathNotSpecifiedException :
        RuntimeException("Genesis file path should be specified through .properties file")