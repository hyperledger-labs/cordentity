# Indy-Cordapp Contracts & States

Corda contract and state classes for the [indy-cordapp](../cordapp/README.md) project.


## States

- [IndyCredential](src/main/kotlin/com/luxoft/blockchainlab/corda/hyperledger/indy/data/state/IndyCredential.kt) - A record of an issued Indy credential

- [IndyCredentialProof](src/main/kotlin/com/luxoft/blockchainlab/corda/hyperledger/indy/data/state/IndyCredentialProof.kt) - A record of an issued Indy proof

- [IndySchema](src/main/kotlin/com/luxoft/blockchainlab/corda/hyperledger/indy/data/state/IndySchema.kt) - A credential schema from. Each credential should be made from such schema.

- [IndyCredentialDefinition](src/main/kotlin/com/luxoft/blockchainlab/corda/hyperledger/indy/data/state/IndyCredentialDefinition.kt) - It encapsulates credential definition and revocation registry definition. 

In Indy world you should create this entities in the next order: Schema -> Credential Definition -> Revocation Registry.
If you are trying to store it in ledger, you should do it in the same order.

Every [IndySchema] can have multiple [IndyCredentialDefinition]s (and also revocation registries) associated with it.
Issuer can issue multiple identical [IndyCredential] with a single [IndyCredentialDefinition] to a single prover.
