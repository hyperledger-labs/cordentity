package com.luxoft.blockchainlab.hyperledger.indy.helpers

import com.natpryce.konfig.*
import java.nio.file.Paths

/**
 * Helps with config retrieving
 */
object ConfigHelper : IndyConfiguration {
    private val CFG_PATH = "indyconfig"
    private val CFG_NAME = "indy.properties"

    /**
     * Returns [indyuser] [Configuration]
     *
     * If looks in test configuration, then it looks in file `indyconfig/indy.properties`,
     * then it looks in environment variables.
     * When using environment variables to pass config make sure you've named your vars correctly:
     * indy.genesisFile will become INDY_GENESISFILE and so on.
     *
     * @return: [Configuration] [indyuser]
     * @throws: [KotlinNullPointerException] - if no configuration at all was found
     */
    val config by lazy {
        val cfgFile = Paths.get(CFG_PATH, CFG_NAME).toFile()

        EmptyConfiguration
            .ifNot(ConfigurationProperties.fromFileOrNull(cfgFile), indyuser)
            .ifNot(EnvironmentVariables(), indyuser)
    }

    override fun getWalletName() = config.getOrNull(indyuser.walletName)

    override fun getGenesisPath() = config.getOrNull(indyuser.genesisFile)

    override fun getDid() = config.getOrNull(indyuser.did)

    override fun getSeed() = config.getOrNull(indyuser.seed)

    override fun getRole() = config.getOrNull(indyuser.role)

    override fun getAgentWSEndpoint() = config.getOrNull(indyuser.agentWSEndpoint)

    override fun getAgentUser() = config.getOrNull(indyuser.agentUser)

    override fun getAgentPassword() = config.getOrNull(indyuser.agentPassword)

    override fun getWalletPassword() = config.getOrNull(indyuser.walletPassword)

    override fun getPoolName() = config.getOrNull(indyuser.poolName)

    override fun getTailsPath() = config.getOrNull(indyuser.tailsPath)
}

@Suppress("ClassName")
object indyuser : PropertyGroup() {
    val role by stringType
    val did by stringType
    val seed by stringType
    val walletName by stringType
    val walletPassword by stringType
    val genesisFile by stringType
    val poolName by stringType
    val agentWSEndpoint by stringType
    val agentUser by stringType
    val agentPassword by stringType
    val tailsPath by stringType
}

interface IndyConfiguration {

    fun getWalletName(): String?

    fun getWalletPassword(): String?

    fun getGenesisPath(): String?

    fun getPoolName(): String?

    fun getDid(): String?

    fun getSeed(): String?

    fun getRole(): String?

    fun getAgentWSEndpoint(): String?

    fun getAgentUser(): String?

    fun getAgentPassword(): String?

    fun getTailsPath(): String?
}