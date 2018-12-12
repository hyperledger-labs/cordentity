# Cordentity

![logo](03_cordentity_app_LOGO_color.svg)

The Cordentity project integrates [Hyperledger Indy](https://www.hyperledger.org/projects/hyperledger-indy) capabilities into the [Corda Platform](https://www.corda.net/index.html).

Hyperledger Indy is a distributed ledger, purpose-built for decentralized identity.

Corda is a blockchain and smart contract platform. Corda allows parties to transact directly, with value. Smart contracts allow Corda to do this using complex agreements and any asset type. This capability has broad applications across industries including finance, supply chain and healthcare.

While Corda is best suited for developing decentralized applications for managing complex inter-organizational workflows, Indy is the leading open-source platform for self sovereign identity. This integration enables the development of decentralized applications on R3's best in class blockchain for business while utilizing the self-sovereign identity capabilities of Indy.

In the identity world, we are often required to prove identity assertions without necessarily wanting to reveal the underlying data. For example, an individual may need to prove that they are of a certain age without wanting to disclose their actual date of birth. Hyperledger Indy uses zero knowledge proofs to enable this functionality, allowing someone prove a statement is true while preserving confidentiality.

## Usage

For full information about the processes and APIs see [cordapp/README](cordapp/README.md)

### Business Case "Legal Age Verification"
   
In many countries a person must be above the legal age to purchase alcohol drinks.
Using services and flows provided by Indy-Codrapp it is possible to create a system 
that proves that the age of a customer is above the legal limit without exposing their actual age or other personal details.
   
Lets assume that those 3 [nodes](cordapp/README.md#corda-terminology) are connected as a part of a Corda network:
 - ministry - the Ministry of Home Affairs service
 - store    - a grocery store payment center
 - alice    - Alice's mobile device
 

    val ministry: StartedNode<*>
    val alice: StartedNode<*>
    val store: StartedNode<*>

Each Corda node has a [X500 name](cordapp/README.md#corda-terminology):

    val ministryX500 = ministry.info.singleIdentity().name
    val aliceX500 = alice.info.singleIdentity().name

And each Indy node has a [DID](cordapp/README.md#indy-terminology), a.k.a Decentralized ID,
which can be obtained using [GetDidFlow](cordapp/README.md#flows):

    val ministryDID = store.services.startFlow(
            GetDidFlow.Initiator(ministryX500)).resultFuture.get()

To allow customers and shops to communicate, Ministry issues a shopping [scheme](cordapp/README.md#indy-terminology)
using [CreateSchemaFlow](cordapp/README.md#flows):

    val schemaId = ministry.services.startFlow(
            CreateSchemaFlow.Authority(
                    "shopping scheme",
                    "1.0",
                    listOf("NAME", "BORN"))).resultFuture.get()

Ministry creates a [credential definition](cordapp/README.md#indy-terminology) for the shopping scheme
using [CreateCredentialDefinitionFlow](cordapp/README.md#flows):

    val credentialDefinitionId = ministry.services.startFlow(
            CreateCredentialDefinitionFlow.Authority(schemaId)).resultFuture.get()

Ministry verifies Alice's legal status and issues her a shopping [credential](cordapp/README.md#indy-terminology)
using [IssueCredentialFlow](cordapp/README.md#flows):

    val credentialProposal = """
        {
        "NAME":{"raw":"Alice", "encoded":"119191919"},
        "BORN":{"raw":"2000",  "encoded":"2000"}
        }
        """

    ministry.services.startFlow(
            IssueCredentialFlow.Issuer(
                    UUID.randomUUID().toString(),
                    credentialDefinitionId,
                    credentialProposal,
                    aliceX500)).resultFuture.get()

When Alice comes to grocery store, the store asks Alice to verify that she is legally allowed to buy drinks
using [VerifyCredentialFlow](cordapp/README.md#flows):

    // Alice.BORN >= currentYear - 18
    val eighteenYearsAgo = LocalDateTime.now().minusYears(18).year
    val legalAgePredicate = VerifyCredentialFlow.ProofPredicate(schemaId, credentialDefinitionId, ministryDID, "BORN", eighteenYearsAgo)

    val verified = store.services.startFlow(
            VerifyCredentialFlow.Verifier(
                    UUID.randomUUID().toString(),
                    emptyList(),
                    listOf(legalAgePredicate),
                    aliceX500)).resultFuture.get()

If the verification succeeds, the store can be sure that Alice's age is above 18.

    println("You can buy drinks: $verified")
    
You can run the whole example as a test in
[ReadmeExampleTest](cordapp/src/test/kotlin/com/luxoft/blockchainlab/corda/hyperledger/indy/ReadmeExampleTest.kt) file.
    

### Business Cases "Personalized Health Care Supply Chain"

Another use case for Indy CorDapp is a [Personalized Health Care Supply Chain](https://github.com/Luxoft/cordentity-poc-supply-chain) project (in early development).

This system allows sharing private patients' information while providing extensive control over the usage of that information.

The connected parties in this case would usually be Insurance Providers, Patients, Hospitals, Personal Medicine Manufacturers and Government Agencies.
The sensitive information may include patient’s age, nationality, results of medical analyses or guarantee of insurance coverage.

Thanks to our Indy CorDapp solution, patient’s personal data is disclosed only to the eligible parties and only to the extent required in each particular business case.

### Installation

    repositories {
        maven { url 'https://jitpack.io' }
    }

    dependencies {
        cordapp "com.github.Luxoft.cordentity:cordapp:0.4.11"
        cordapp "com.github.Luxoft.cordentity:cordapp-contracts-states:0.4.11"
    }

On all machines that are going to run [IndyService](cordapp/README.md#services) install the `libindy` package:

    sudo apt-key adv --keyserver keyserver.ubuntu.com --recv-keys 68DB5E88
    sudo add-apt-repository "deb https://repo.sovrin.org/sdk/deb xenial stable"
    sudo apt-get update
    sudo apt-get install -y libindy=1.6.4
    
Please follow to the official [indy-sdk repo](https://github.com/hyperledger/indy-sdk#installing-the-sdk) 
for installation instructions for Windows, iOS, Android and MacOS.

# Development
 

## Subprojects

- [indy-cordapp](cordapp/README.md) - the cordapp
- [indy-cordapp-contracts-states](cordapp-contracts-states/README.md) - contracts for the cordapp
- [indy-utils](indy-utils/README.md) - utilities for working with the Indy SDK

## External dependancies

Version cordapp 0.4.11 requires installation of indy-sdk version 1.6.4.

## Build

To run the tests you need to install the `libindy` package:

    sudo apt-key adv --keyserver keyserver.ubuntu.com --recv-keys 68DB5E88
    sudo add-apt-repository "deb https://repo.sovrin.org/sdk/deb xenial stable"
    sudo apt-get update
    sudo apt-get install -y libindy=1.6.4
    
Make sure that `Docker` is installed:

    sudo apt update
    sudo apt install docker
    
After that use the standard Gradle build procedure:

    gradle clean build
    
### Troubleshooting    
    
Before every test run it is recommended to clean local pool and wallets data, which by default are stored in `~/.indy_client/`:

    gradle cleanDefaultPool
    
Also re-creating the `indypool` docker container is needed to get a clean system:

    gradle dockerCleanRun

To manually start the `indy-pool` container on ports 9701-9708: 

    docker pull teamblockchain/indy-pool:1.6.4
    docker create -p 9701-9708:9701-9708 --name indypool --rm teamblockchain/indy-pool:1.6.4
    docker start indypool
    
## Contributors

- [Alexander Kopnin](https://github.com/alkopnin)
- [Alexey Koren](https://github.com/alexeykoren)
- [Daniil Vodopian](https://github.com/voddan/)
