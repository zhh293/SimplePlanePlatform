package com.proxy.android

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Bounded UTF-8 transfers shared by document-provider and unit tests. */
internal object ConfigFiles {
    const val MAX_BYTES = 256 * 1024

    fun read(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= MAX_BYTES) { "配置文件不能超过 256 KB" }
            bytes.write(buffer, 0, count)
        }
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    }

    fun backup(config: AndroidVpnConfig): String {
        require(config.validate() == null) { config.validate().orEmpty() }
        return config.toJsonString(2).also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "配置文件不能超过 256 KB" }
        }
    }

    fun write(output: OutputStream, text: String) {
        output.write(text.toByteArray(Charsets.UTF_8))
        output.flush()
    }
}
