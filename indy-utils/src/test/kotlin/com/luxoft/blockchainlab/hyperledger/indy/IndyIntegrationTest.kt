package com.luxoft.blockchainlab.hyperledger.indy


open class IndyIntegrationTest {
    protected var GVT_SCHEMA_NAME = "gvt"
    protected var XYZ_SCHEMA_NAME = "xyz"
    protected var SCHEMA_VERSION = "1.0"
    protected var GVT_SCHEMA_ATTRIBUTES = listOf("name", "age", "sex", "height")
    protected var XYZ_SCHEMA_ATTRIBUTES = listOf("status", "period")
    protected var GVT_CRED_VALUES = "{\n" +
            "        \"sex\": {\"raw\": \"male\", \"encoded\": \"5944657099558967239210949258394887428692050081607692519917050\"},\n" +
            "        \"name\": {\"raw\": \"Alex\", \"encoded\": \"1139481716457488690172217916278103335\"},\n" +
            "        \"height\": {\"raw\": \"175\", \"encoded\": \"175\"},\n" +
            "        \"age\": {\"raw\": \"28\", \"encoded\": \"28\"}\n" +
            "    }"
    protected var CREDENTIALS = "{\"key\": \"key\"}"
    protected var PROTOCOL_VERSION = 2

    protected val TRUSTEE_SEED = "000000000000000000000000Trustee1"
    protected val MY1_SEED = "00000000000000000000000000000My1"
    protected val MY2_SEED = "00000000000000000000000000000My2"
    protected val VERKEY_MY1 = "GjZWsBLgZCR18aL468JAT7w9CZRiBnpxUPPgyQxh4voa"
    protected val VERKEY_MY2 = "kqa2HyagzfMAq42H5f9u3UMwnSBPQx2QfrSyXbUPxMn"
    protected val VERKEY_TRUSTEE = "GJ1SzoWzavQYfNL9XkaJdrQejfztN4XqdsiV4ct3LXKL"
    protected val DID_MY1 = "VsKV7grR1BUE29mG2Fm2kX"
    protected val DID_MY2 = "2PRyVHmkXQnQzJQKxHxnXC"
    protected val DID_TRUSTEE = "V4SGRU86Z58d6TV7PBUe6f"
    protected val TYPE = "default"

    companion object {
        val TEST_GENESIS_FILE_PATH by lazy {
            this::class.java.classLoader.getResource("docker_pool_transactions_genesis.txt").file
        }
    }
}
