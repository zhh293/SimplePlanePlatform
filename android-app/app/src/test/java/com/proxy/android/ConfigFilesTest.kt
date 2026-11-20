package com.proxy.android

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.CharacterCodingException
import org.junit.Assert.*
import org.junit.Test

class ConfigFilesTest {
    @Test fun backupRoundTripPreservesEverySettingAndRuleOrder() {
        val base = AndroidVpnConfig.defaultConfig().remotes.single()
        val config = AndroidVpnConfig(
            mtu = 1400,
            remotes = listOf(base.copy(name = "停用节点", enabled = false), base.copy(name = "手动节点", key = "quote\"\\#中文", serverName = "proxy.example", caPem = "certificate\nline two\n")),
            selectedNodeIndex = 1,
            routing = AndroidRoutingConfig("direct", false, listOf(
                RouteRuleConfig("port", "443", "reject"),
                RouteRuleConfig("domain_pattern", "*.example.com", "proxy"),
                RouteRuleConfig("ip_cidr", "192.0.2.0/24", "direct"),
            )),
        )
        val output = ByteArrayOutputStream()
        ConfigFiles.write(output, ConfigFiles.backup(config))
        val imported = AndroidVpnConfig.fromImportText(ConfigFiles.read(ByteArrayInputStream(output.toByteArray())))
        assertEquals(config, imported)
    }

    @Test fun acceptsUtf8Bom() {
        val config = AndroidVpnConfig.defaultConfig()
        val stream = ByteArrayInputStream(("\uFEFF" + ConfigFiles.backup(config)).toByteArray(Charsets.UTF_8))
        assertEquals(config, AndroidVpnConfig.fromImportText(ConfigFiles.read(stream)))
    }

    @Test fun rejectsOversizedFilesWithoutReadingTheWholeSource() {
        val stream = ByteArrayInputStream(ByteArray(ConfigFiles.MAX_BYTES * 2) { 'a'.code.toByte() })
        assertThrows(IllegalArgumentException::class.java) { ConfigFiles.read(stream) }
        assertTrue(stream.available() > 0)
        assertEquals(ConfigFiles.MAX_BYTES, ConfigFiles.read(ByteArrayInputStream(ByteArray(ConfigFiles.MAX_BYTES) { 65 })).length)
    }

    @Test fun rejectsInvalidUtf8InsteadOfReplacingCredentialBytes() {
        assertThrows(CharacterCodingException::class.java) {
            ConfigFiles.read(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)))
        }
    }
}
