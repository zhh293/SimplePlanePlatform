package com.proxy.android

import org.junit.Assert.*
import org.junit.Test

class AndroidVpnConfigTest {
    @Test fun v2SuitesKeepTheirConfigurationAndRequire32Utf8Bytes() {
        for (suite in listOf("chacha20-poly1305-v2", "aes-gcm-v2")) {
            val node = AndroidVpnConfig.defaultConfig().remotes.single().copy(
                host = "::1", cipher = suite, key = "0123456789abcdef0123456789abcdef",
            )
            val config = AndroidVpnConfig(remotes = listOf(node))
            assertNull(config.validate())
            assertEquals(config, AndroidVpnConfig.fromJson(config.toJsonString()))
            val imported = AndroidVpnConfig.fromImportText(config.toDesktopYaml()).remotes.single()
            assertEquals(node.host, imported.host)
            assertEquals(node.cipher, imported.cipher)
            assertEquals(node.key, imported.key)
            assertEquals(node.transport, imported.transport)
            assertNotNull(config.copy(remotes = listOf(node.copy(key = "short"))).validate())
            assertNotNull(config.copy(remotes = listOf(node.copy(key = "密".repeat(32)))).validate())
        }
    }

    @Test fun http3JsonRoundTripKeepsTransportAndTrust() {
        val config = AndroidVpnConfig.defaultConfig().let { it.copy(remotes = listOf(
            it.remotes.single().copy(serverName = "proxy.example", caPem = "private CA\n"),
        )) }
        assertEquals(config, AndroidVpnConfig.fromJson(config.toJsonString()))
        assertEquals("http3", config.remotes.single().transport)
        assertNull(config.validate())
        val yamlNode = AndroidVpnConfig.fromImportText(config.toDesktopYaml()).remotes.single()
        assertEquals(config.remotes.single().serverName, yamlNode.serverName)
        assertEquals(config.remotes.single().caPem, yamlNode.caPem)
    }

    @Test fun desktopYamlKeepsHttp3InsteadOfSilentlyUsingHttp2() {
        val config = AndroidVpnConfig.fromImportText("""
            remoteServers:
              - host: 54.172.101.190
                port: 9090
                cipher: chacha20
                cipherKey: "test-key"
                transport: http3
        """.trimIndent())
        val exported = AndroidVpnConfig.fromImportText(config.toDesktopYaml())
        assertEquals("http3", exported.remotes.single().transport)
    }

    @Test fun missingTransportInLegacyConfigurationStaysHttp2() {
        val config = AndroidVpnConfig.fromJson("""{"remotes":[{"host":"my-node","port":9090,"key":"k"}]}""")
        assertEquals("http2", config.remotes.single().transport)
        assertEquals(config, VpnConfigStore.migrateLegacyDefault(config))
    }

    @Test fun untouchedJulyDefaultMigratesWithoutChangingRouting() {
        val old = AndroidVpnConfig.defaultConfig().let { it.copy(remotes = listOf(
            it.remotes.single().copy(host = "54.234.196.30", transport = "http2", tls = false),
        )) }
        val migrated = VpnConfigStore.migrateLegacyDefault(old)
        assertEquals("54.172.101.190", migrated.remotes.single().host)
        assertEquals("http3", migrated.remotes.single().transport)
        assertTrue(migrated.remotes.single().tls)
        assertEquals(old.routing, migrated.routing)
        assertEquals(migrated, VpnConfigStore.migrateLegacyDefault(migrated))
    }

    @Test fun customizedCredentialsAndMultiNodeListsAreNeverMigrated() {
        val old = AndroidVpnConfig.defaultConfig().remotes.single().copy(
            host = "54.234.196.30", transport = "http2", tls = false,
        )
        val custom = AndroidVpnConfig(remotes = listOf(old.copy(key = "my-key")))
        val multiple = AndroidVpnConfig(remotes = listOf(old, old.copy(host = "my-node")))
        assertEquals(custom, VpnConfigStore.migrateLegacyDefault(custom))
        assertEquals(multiple, VpnConfigStore.migrateLegacyDefault(multiple))
    }
}
