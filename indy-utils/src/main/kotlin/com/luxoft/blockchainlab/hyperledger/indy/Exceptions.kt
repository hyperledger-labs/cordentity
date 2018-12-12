package com.luxoft.blockchainlab.hyperledger.indy


class IndyCredentialDefinitionAlreadyExistsException(schemaId: String, msg: String) :
    IllegalArgumentException("Credential definition for schema: $schemaId is already exists")

class IndyCredentialMaximumReachedException(revRegId: String) :
    IllegalArgumentException("Revocation registry with id: $revRegId cannot hold more credentials")

class IndySchemaAlreadyExistsException(name: String, version: String) :
    IllegalArgumentException("Schema with name $name and version $version already exists")

class IndySchemaNotFoundException(id: String, msg: String) :
    IllegalArgumentException("There is no schema with id: $id. $msg")

class IndyRevRegNotFoundException(id: String, msg: String) :
    IllegalArgumentException("There is no revocation registry with id: $id. $msg")

class IndyRevDeltaNotFoundException(id: String, msg: String) :
    IllegalArgumentException("Revocation registry delta $id for definition doesn't exist in ledger. $msg")

class IndyCredentialDefinitionNotFoundException(id: String, msg: String) :
    IllegalArgumentException("There is no credential definition with id: $id. $msg")