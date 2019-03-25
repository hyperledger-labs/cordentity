package com.luxoft.blockchainlab.hyperledger.indy.models

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Represents revocation registry definition
 *
 * Example:
 * {
 *  "ver":"1.0",
 *  "id":"V4SGRU86Z58d6TV7PBUe6f:4:V4SGRU86Z58d6TV7PBUe6f:3:CL:11:TAG_1:CL_ACCUM:REV_TAG_1",
 *  "revocDefType":"CL_ACCUM",
 *  "tag":"REV_TAG_1",
 *  "credDefId":"V4SGRU86Z58d6TV7PBUe6f:3:CL:11:TAG_1",
 *  "value":{
 *      "issuanceType":"ISSUANCE_ON_DEMAND",
 *      "maxCredNum":10000,
 *      "publicKeys":{
 *          "accumKey":{
 *              "z":"DD5CB7C7B73632 4AFF49D65DC22B 75DCE6B4720E9A 6850C5B997857B 24A81D0A FB91DEDCC2933B 58F8253DDC2932 A70370A1A6B790 B7C1D0EA96C211 1F9AC413 42BCA194D89D6E 2CA77CB3C7D22A 5E2004C628FE02 E3AF249480D877 1078CDD3 1899F0C8F69EF0 6E6597A07EFCBE 3050DA53AC48F 138D31F5D0F836 20DD73AA E8CBC1334EAC3E 6221F7D1C21FBC AB5605E23860D7 BBF7B256371799 2E756F8 C7ECC90D700DAB D1A7EEED09CB33 DA218E8EC0C2E7 93EC5FF2FE457C 1861FA59 63FB8BC55D915 B726AE490AC56C 49ED7DEFC0988D 60815A62EC29CD 1D1E8504 76C9A801569840 5E417CE5540DCD 77FCEDF0A5DD9 47D9C0D070C4B1 23315D95 87773524083058 E75B5A54FF24F 33148931C2BB4E 426BBE4DC6AA66 3C910CC 66C14B91B5D70A FD94681339A7B5 D27A926A28D6AE 385A898772ED98 797FFA8 5A591894E431CF 582624540B9B0C 28E11C07575D81 8D96EE6F5EFB27 16D6BD9B DF160431970E42 3AAE8325F8F8B8 93D8C65022890A 485AFA07D3F281 423B31C"
 *          }
 *      },
 *      "tailsHash":"FU6TF1Tw8D2Pk8MT8y5DVZSBUJqq3WdGYvTm3oGU2hYS",
 *      "tailsLocation":"/home/joinu/.indy_client/tails/FU6TF1Tw8D2Pk8MT8y5DVZSBUJqq3WdGYvTm3oGU2hYS"
 *  }
 * }
 */

data class RevocationRegistryInfo(
    val definition: RevocationRegistryDefinition,
    val entry: RevocationRegistryEntry
)

data class RevocationRegistryDefinition(
    val ver: String,
    val id: String,
    @JsonProperty("revocDefType") val revocationRegistryDefinitionType: String,
    val tag: String,
    @JsonProperty("credDefId") override val credentialDefinitionIdRaw: String,
    val value: RawJsonMap,
    @JsonIgnore override val revocationRegistryIdRaw: String? = id
) : ContainsCredentialDefinitionId, ContainsRevocationRegistryId

/**
 * Represents revocation registry entry
 *
 * Example
 * {
 *  "ver":"1.0",
 *  "value":{
 *      "accum":"true 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0"
 *  }
 * }
 */
data class RevocationRegistryEntry(
    val ver: String,
    val value: RawJsonMap
)

data class RevocationState(
    val witness: RawJsonMap,
    @JsonProperty("rev_reg") val revocationRegistry: RawJsonMap,
    val timestamp: Long,
    @JsonIgnore override var revocationRegistryIdRaw: String? = null
) : ContainsRevocationRegistryId

/**
 * Allows to configure revocation registry creation
 */
data class RevocationRegistryConfig(
    val issuanceType: String,
    @JsonProperty("max_cred_num") val maximumCredentialNumber: Int
)

/**
 * Revocation registry definition id is the local identifier of some revocation registry. This class (de)serializes
 * revocation registry definition id.
 * Indy uses raw string value of revocation registry definition id, but it's parts are actually very useful.
 * By possessing some revocation registry definition id id one could also know its:
 *
 * @param did: [String] - the DID of the revocation registry issuer
 * @param credentialDefinitionId: [CredentialDefinitionId] - the id of credential definition which
 *  this revocation registry accompanies
 * @param tag: [String] - the tag of the revocation registry definition (used for versioning)
 */
data class RevocationRegistryDefinitionId(
    val did: String,
    override val credentialDefinitionIdRaw: String,
    val tag: String
): ContainsCredentialDefinitionId {
    override fun toString() = "$did:4:$credentialDefinitionIdRaw:CL_ACCUM:$tag"

    companion object : FromString<RevocationRegistryDefinitionId> {
        override fun fromString(str: String): RevocationRegistryDefinitionId {
            val strSplitted = str.split(":")
            val didRev = strSplitted[0]
            val tagRev = strSplitted[strSplitted.lastIndex]
            val didCred = strSplitted[2]
            val tagCred = strSplitted[strSplitted.lastIndex - 2]

            val seqNo = strSplitted[5].toInt()

            return RevocationRegistryDefinitionId(didRev, CredentialDefinitionId(didCred, seqNo, tagCred).toString(), tagRev)
        }
    }
}

/**
 * Represents a class which somehow provides revocation registry definition id
 */
interface ContainsRevocationRegistryId {
    val revocationRegistryIdRaw: String?
    fun getRevocationRegistryIdObject() =
        if (revocationRegistryIdRaw == null) null
        else RevocationRegistryDefinitionId.fromString(revocationRegistryIdRaw!!)
}