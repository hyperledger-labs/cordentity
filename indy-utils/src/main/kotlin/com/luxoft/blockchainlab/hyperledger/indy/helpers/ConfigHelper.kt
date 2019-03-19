package com.luxoft.blockchainlab.hyperledger.indy.helpers

import com.natpryce.konfig.*
import java.nio.file.Path
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
    fun getConfig(path: Path = Paths.get(CFG_PATH, CFG_NAME)): Configuration {
        val cfgFile = path.toFile()

        return EmptyConfiguration
            .ifNot(ConfigurationProperties.fromFileOrNull(cfgFile), indyuser)
            .ifNot(EnvironmentVariables(), indyuser)
    }

    override fun getWalletName() = getConfig()[indyuser.walletName]

    override fun getGenesisPath() = getConfig()[indyuser.genesisFile]

    override fun getDid() = getConfig()[indyuser.did]

    override fun getSeed() = getConfig()[indyuser.seed]

    override fun getRole() = getConfig()[indyuser.role]

    override fun getAgentWSEndpoint() = getConfig()[indyuser.agentWSEndpoint]

    override fun getAgentUser() = getConfig()[indyuser.agentUser]

    override fun getAgentPassword() = getConfig()[indyuser.agentPassword]

    override fun getWalletPassword() = getConfig()[indyuser.walletPassword]
}

@Suppress("ClassName")
object indyuser : PropertyGroup() {
    val role by stringType
    val did by stringType
    val seed by stringType
    val walletName by stringType
    val walletPassword by stringType
    val genesisFile by stringType
    val agentWSEndpoint by stringType
    val agentUser by stringType
    val agentPassword by stringType
}

interface IndyConfiguration {

    fun getWalletName(): String

    fun getWalletPassword(): String

    fun getGenesisPath(): String

    fun getDid(): String

    fun getSeed(): String

    fun getRole(): String

    fun getAgentWSEndpoint(): String

    fun getAgentUser(): String

    fun getAgentPassword(): String
}