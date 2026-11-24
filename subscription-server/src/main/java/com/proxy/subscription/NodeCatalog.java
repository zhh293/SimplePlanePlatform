package com.proxy.subscription;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.transaction.annotation.Transactional;

@Component
@EnableConfigurationProperties(NodeCatalog.Settings.class)
public class NodeCatalog implements ApplicationRunner {
    @ConfigurationProperties("subscription.catalog")
    public record Settings(List<Node> nodes) {}
    public record Node(String id, String groupId, String name, String host, int port, String serverName,
                       Path serverCa, Path signerStore, String signerAlias, String signerPassword, String cipherKey) {
        @Override public String toString() { return "subscription node " + id; }
    }
    public record IssuedNode(String id, String name, String host, int port, String transport,
                             String serverName, String caPem, String deviceCertificatePem, String cipher, String key) {
        @Override public String toString() { return "issued node " + id; }
    }
    private final Settings settings;
    private final JdbcTemplate db;
    private final NodeMaterial vault;
    private final SecureRandom random = new SecureRandom();
    private record Stored(String id,String label,String host,int port,String serverName,String ca,byte[] material) {}

    NodeCatalog(Settings settings, JdbcTemplate db, NodeMaterial vault) {
        this.settings=settings;
        this.db=db;
        this.vault=vault;
    }

    @Override @Transactional(rollbackFor=Exception.class)
    public void run(ApplicationArguments args) throws Exception {
        Set<String> ids = new HashSet<>();
        Set<String> signers = new HashSet<>();
        for (Node node : Objects.requireNonNullElse(settings.nodes(), List.<Node>of())) {
            if (db.queryForObject("SELECT COUNT(*) FROM sp_imported_node WHERE node_id=?",Integer.class,node.id())!=0) continue;
            if (node.id() == null || !node.id().matches("[A-Za-z0-9_-]{1,96}") || !ids.add(node.id())
                    || node.groupId() == null || !node.groupId().matches("[A-Za-z0-9_-]{1,96}")
                    || node.name() == null || node.name().isBlank() || node.host() == null || node.host().isBlank()
                    || node.serverName() == null || node.serverName().isBlank()
                    || node.port() < 1 || node.port() > 65535 || node.signerPassword() == null
                    || node.signerPassword().isBlank() || node.cipherKey() == null
                    || node.cipherKey().getBytes(java.nio.charset.StandardCharsets.UTF_8).length != 32)
                throw new IllegalArgumentException("Invalid subscription node");
            KeyStore store = KeyStore.getInstance("PKCS12");
            char[] password = node.signerPassword().toCharArray();
            try (InputStream input = Files.newInputStream(node.signerStore())) {
                store.load(input, password);
                PrivateKey key = (PrivateKey) store.getKey(node.signerAlias(), password);
                X509Certificate ca = (X509Certificate) store.getCertificate(node.signerAlias());
                if (key == null || ca == null || ca.getBasicConstraints() < 0 || !"EC".equals(key.getAlgorithm())
                        || !signers.add(Base64.getEncoder().encodeToString(ca.getPublicKey().getEncoded())))
                    throw new IllegalArgumentException("Each protected node requires its own EC signing CA");
                ca.checkValidity();
                String publicCa;
                try (InputStream serverTrust = Files.newInputStream(node.serverCa())) {
                    publicCa = pem(((X509Certificate) CertificateFactory.getInstance("X.509")
                            .generateCertificate(serverTrust)).getEncoded());
                }
                db.update("INSERT INTO sp_imported_node(node_id) VALUES(?)",node.id());
                db.update("INSERT INTO sp_group(group_id,label) VALUES(?,?) ON DUPLICATE KEY UPDATE group_id=VALUES(group_id)",node.groupId(),node.groupId());
                db.update("INSERT INTO sp_node(node_id,label,host,port,server_name,enabled,configured,server_ca,sealed_material) VALUES(?,?,?,?,?,true,true,?,?)",
                        node.id(),node.name(),node.host(),node.port(),node.serverName(),publicCa,
                        vault.seal(node.id(),new NodeMaterial.Keys(key,ca,node.cipherKey())));
                db.update("INSERT INTO sp_group_node(group_id,node_id) VALUES(?,?)",node.groupId(),node.id());
            } finally {
                Arrays.fill(password, '\0');
            }
        }
    }

    List<IssuedNode> issue(String group, String device, byte[] spki, Instant expires) {
        List<IssuedNode> nodes = new ArrayList<>();
        try {
            var publicKey = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spki));
            var stored=db.query("SELECT n.* FROM sp_node n JOIN sp_group_node g ON n.node_id=g.node_id WHERE g.group_id=? AND n.enabled=true ORDER BY n.node_id",
                    (r,i)->new Stored(r.getString("node_id"),r.getString("label"),r.getString("host"),r.getInt("port"),r.getString("server_name"),
                            r.getString("server_ca"),r.getBytes("sealed_material")),group);
            for (var n : stored) {
                var keys=vault.open(n.id(),n.material());
                keys.issuer().checkValidity(Date.from(expires));
                var cert = new JcaX509v3CertificateBuilder(keys.issuer(),
                        new BigInteger(159, random).setBit(158), Date.from(Instant.now().minusSeconds(5)),
                        Date.from(expires), new X500Name("CN=" + UUID.fromString(device)), publicKey);
                cert.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
                cert.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
                cert.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
                X509Certificate leaf = new JcaX509CertificateConverter().getCertificate(cert.build(
                        new JcaContentSignerBuilder("SHA256withECDSA").build(keys.signer())));
                nodes.add(new IssuedNode(n.id(),n.label(),n.host(),n.port(),"http3",n.serverName(),n.ca(),
                        pem(leaf.getEncoded()),"aes-gcm-v2",keys.frame()));
            }
            return nodes;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not issue device credential", failure);
        }
    }

    static String pem(byte[] der) {
        return "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der)
                + "\n-----END CERTIFICATE-----\n";
    }
}
