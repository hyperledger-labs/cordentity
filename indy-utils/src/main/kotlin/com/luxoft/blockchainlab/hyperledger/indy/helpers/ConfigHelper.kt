package com.luxoft.blockchainlab.hyperledger.indy.helpers

import com.natpryce.konfig.*
import java.io.File

/**
 * Helps with config retrieving
 */
object ConfigHelper {
    /**
     * Returns [indyuser] [Configuration]
     *
     * If looks in test configuration of name [name], then it looks in file `indyconfig/indy.properties`,
     * then it looks in file `indyconfig/[name].indy.properties` and finally it looks in environment variables.
     * When using environment variables to pass config make sure you've named your vars correctly:
     * indy.genesisFile will become INDY_GENESISFILE and so on.
     *
     * @param name: [String] - configuration name (corda node name, for example)
     * @return: [Configuration] [indyuser]
     * @throws: [KotlinNullPointerException] - if no configuration at all was found
     */
    fun getConfig(name: String): Configuration {

        return TestConfigurationsProvider.config(name)
            ?: EmptyConfiguration
                .ifNot(
                    ConfigurationProperties.fromFileOrNull(File("indyconfig", "indy.properties")),
                    indyuser
                ) // file with common name if you go for file-based config
                .ifNot(
                    ConfigurationProperties.fromFileOrNull(File("indyconfig", "$name.indy.properties")),
                    indyuser
                )  //  file with node-specific name
                .ifNot(EnvironmentVariables(), indyuser) // Good for docker-compose, ansible-playbook or similar
    }
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