package com.proxy.android

import org.junit.Assert.*
import org.junit.Test

class AndroidVpnConfigTest {
    @Test fun manualSelectionUsesOnlyTheChosenNodeEvenWithDuplicateNames() {
        val node = AndroidVpnConfig.defaultConfig().remotes.single()
        val config = AndroidVpnConfig(remotes = listOf(node.copy(host = "first.invalid"), node), selectedNodeIndex = 1)
        assertEquals(config, AndroidVpnConfig.fromJson(config.toJsonString()))
        assertEquals(listOf(node), config.forConnection().remotes)
        assertEquals(-1, config.forConnection().selectedNodeIndex)
        assertEquals(config.remotes, config.copy(selectedNodeIndex = -1).forConnection().remotes)
    }

    @Test fun missingOrDisabledSelectionCannotSilentlyFallBack() {
        val config = AndroidVpnConfig.defaultConfig()
        for (selected in listOf(-2, 1, 200)) {
            assertNotNull(config.copy(selectedNodeIndex = selected).validate())
            assertThrows(IllegalArgumentException::class.java) { config.copy(selectedNodeIndex = selected).forConnection() }
        }
        val disabled = config.copy(remotes = listOf(config.remotes.single().copy(enabled = false), config.remotes.single()), selectedNodeIndex = 0)
        assertNotNull(disabled.validate())
        assertThrows(IllegalArgumentException::class.java) { disabled.forConnection() }
        assertEquals(1, disabled.copy(selectedNodeIndex = -1).forConnection().remotes.size)
    }

    @Test fun fileImportRejectsMissingAndEmptyNodeLists() {
        for (text in listOf("{}", "{\"remotes\":[]}", "{\"remotes\":[null]}", "{\"remotes\":\"invalid\"}", "not a configuration", "route:\n  defaultRoute: direct")) {
            assertThrows("Unexpected default-node fallback for $text", IllegalArgumentException::class.java) {
                AndroidVpnConfig.fromImportText(text)
            }
        }
    }

    @Test fun importedLegacyConfigKeepsAutomaticMode() {
        val config = AndroidVpnConfig.fromImportText("""{"remote_host":"proxy.example","remote_key":"test","remote_port":9090}""")
        assertEquals(-1, config.selectedNodeIndex)
        assertEquals("proxy.example", config.forConnection().remotes.single().host)
    }

    @Test fun yamlHashInsideCredentialsDoesNotStartAComment() {
        for ((yaml, key) in listOf("\"alpha#beta\" # comment" to "alpha#beta", "'alpha#beta'" to "alpha#beta", "alpha#beta" to "alpha#beta")) {
            val config = AndroidVpnConfig.fromImportText("""
                remoteServers:
                  - host: proxy.example
                    port: 9090
                    cipherKey: $yaml
            """.trimIndent())
            assertEquals(key, config.remotes.single().key)
        }
    }

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
