package com.proxy.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/** Called by the activity's worker and by Rust's blocking credential worker. */
internal object NodeSubscription {
    private const val origin = "https://212.64.14.155"
    private const val alias = "SimplePlane.Subscription.Device.v1"
    private val nodeId = Regex("[A-Za-z0-9_-]{1,96}")
    private val shortCode = Regex("[A-Z2-9]{12}")
    private data class Lease(val expiry: Long, val identities: Map<String, ByteArray>)
    private var lease: Lease? = null
    private fun prefs(context: Context) = context.getSharedPreferences("subscription-device", Context.MODE_PRIVATE)
    private fun now() = System.currentTimeMillis() / 1000
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    fun bound(context: Context) = prefs(context).contains("device")

    private fun post(context: Context, path: String, input: JSONObject): JSONObject {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val physical = manager.allNetworks.firstOrNull {
            val caps = manager.getNetworkCapabilities(it)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } ?: error("没有可用的物理网络")
        val connection = physical.openConnection(URL("$origin/device/$path")) as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(input.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            check(status == 200) { "订阅服务返回 HTTP $status" }
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(bytes.size() + count <= 2 * 1024 * 1024) { "订阅清单过大" }
                    bytes.write(buffer, 0, count)
                }
            }
            return JSONObject(bytes.toString("UTF-8"))
        } finally { connection.disconnect() }
    }

    private fun sign(message: String): String {
        val key = store().getKey(alias, null) as? java.security.PrivateKey ?: error("设备密钥不可用，请重新绑定")
        return b64(Signature.getInstance("SHA256withECDSA").run {
            initSign(key); update(message.toByteArray(Charsets.US_ASCII)); sign()
        })
    }

    data class Pairing(val code: String, val uri: String, internal val secret: String, val expiry: Long) {
        override fun toString() = "Pairing(expiry=$expiry)"
    }

    @Synchronized fun start(context: Context): Pairing {
        check(!bound(context)) { "请先退出当前账号" }
        if (!store().containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
        }
        val key = store().getCertificate(alias).publicKey
        val json = post(context, "pair", JSONObject().put("name",Build.MODEL.take(80)).put("publicKey",b64(key.encoded)))
        val code = json.getString("userCode")
        val url = json.getString("verificationUri")
        require(shortCode.matches(code) && url == "$origin/account/pair/$code") { "设备确认地址无效" }
        return Pairing(code, url, json.getString("deviceSecret"), json.getLong("expiresAt"))
    }

    @Synchronized fun poll(context: Context, pair: Pairing): String {
        if (now() >= pair.expiry) return "expired"
        val json = post(context, "poll", JSONObject().put("deviceSecret",pair.secret)
            .put("signature",sign("simpleplane-pair-v1\n${pair.secret}")))
        val status = json.getString("status")
        if (status == "approved") {
            val id = UUID.fromString(json.getString("deviceId")).toString()
            check(prefs(context).edit().putString("device",id).commit()) { "设备绑定保存失败" }
        }
        return status
    }

    @Synchronized fun sync(context: Context): Int {
        val id = prefs(context).getString("device",null) ?: error("请先登录订阅账号")
        val challenge = post(context,"$id/challenge",JSONObject()).getString("challenge")
        val tls = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val pub = b64(tls.public.encoded)
        val result = post(context,"$id/manifest",JSONObject().put("challenge",challenge).put("tlsPublicKey",pub)
            .put("signature",sign("simpleplane-manifest-v1\n$id\n$challenge\n$pub")))
        val expiry = result.getLong("expiresAt")
        require(result.getInt("version")==1 && expiry>now()) { "节点清单已过期或版本无效" }
        val nodes = result.getJSONArray("nodes")
        require(nodes.length() <= 128)
        val keys = mutableMapOf<String,ByteArray>()
        val managed = mutableListOf<RemoteNodeConfig>()
        val certificates = CertificateFactory.getInstance("X.509")
        for (i in 0 until nodes.length()) {
            val n = nodes.getJSONObject(i)
            val node = n.getString("id")
            require(nodeId.matches(node) && node !in keys && n.getString("transport") == "http3")
            val ca = n.getString("caPem")
            certificates.generateCertificate(ca.byteInputStream())
            val cert = certificates.generateCertificate(n.getString("deviceCertificatePem").byteInputStream())
            require(cert.publicKey.encoded.contentEquals(tls.public.encoded)) { "凭证与本机密钥不匹配" }
            val pk = tls.private.encoded
            keys[node] = ByteBuffer.allocate(2+pk.size+cert.encoded.size).putShort(pk.size.toShort()).put(pk).put(cert.encoded).array()
            val config = RemoteNodeConfig(n.getString("name"), n.getString("host"), n.getInt("port"), n.getString("key"),
                n.getString("cipher"), true, true, "http3", n.getString("serverName"), ca, node)
            require(config.cipher == "aes-gcm-v2" && AndroidVpnConfig(remotes=listOf(config)).validate() == null) { "节点参数无效" }
            managed.add(config)
        }
        merge(context, managed)
        lease = Lease(expiry, keys)
        return managed.size
    }

    private fun merge(context: Context, managed: List<RemoteNodeConfig>) {
        val cfg = VpnConfigStore.load(context)
        val selected = cfg.remotes.getOrNull(cfg.selectedNodeIndex)
        val all = cfg.remotes.filter { it.subscriptionId.isEmpty() } + managed
        val index = if (selected == null) -1 else all.indexOfFirst {
            if (selected.subscriptionId.isEmpty()) it == selected else it.subscriptionId == selected.subscriptionId
        }
        VpnConfigStore.save(context,cfg.copy(remotes=all,selectedNodeIndex=index))
    }

    @Synchronized fun credential(context: Context, id: String): ByteArray {
        require(nodeId.matches(id))
        if ((lease?.expiry ?: 0) <= now()+15) sync(context)
        return lease?.identities?.get(id)?.clone() ?: error("设备没有此节点的授权")
    }

    @Synchronized fun logout(context: Context) {
        merge(context,emptyList())
        lease = null
        store().deleteEntry(alias)
        check(prefs(context).edit().remove("device").commit())
    }
}
