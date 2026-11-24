package com.proxy.subscription;

import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Autowired;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(EnrollmentIntegrationTest.Config.class)
class EnrollmentIntegrationTest {
    @Configuration
    @EnableTransactionManagement(proxyTargetClass=true)
    static class Config {
        @Bean DataSource dataSource() {
            var source = new DriverManagerDataSource("jdbc:h2:mem:subscription;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__subscriptions.sql"),
                    new ClassPathResource("db/migration/V2__operations.sql")).execute(source);
            new JdbcTemplate(source).update("INSERT INTO sp_group(group_id,label) VALUES('group-a','Test')");
            return source;
        }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean JdbcTemplate db(DataSource source) { return new JdbcTemplate(source); }
        @Bean EnrollmentService enrollment(JdbcTemplate db) {
            return new EnrollmentService(db, "https://127.0.0.1", "https://issuer.test");
        }
        @Bean NodeMaterial vault() throws Exception {return new NodeMaterial(Base64.getEncoder().encodeToString(new byte[32]));}
        @Bean NodeCatalog catalog(JdbcTemplate db,NodeMaterial vault) { return new NodeCatalog(new NodeCatalog.Settings(List.of()),db,vault); }
        @Bean DeviceController devices(EnrollmentService service, NodeCatalog catalog, JdbcTemplate db) {
            return new DeviceController(service, catalog, db, Base64.getEncoder().encodeToString(new byte[32]));
        }
    }

    @Autowired EnrollmentService service;
    @Autowired DeviceController api;
    @Autowired JdbcTemplate db;
    @Autowired NodeCatalog catalog;
    @Autowired NodeMaterial vault;

    @Test void consoleAssignmentsAffectActualManifestsAndNeverExportSignerKeys() throws Exception {
        ConsoleService ops=new ConsoleService(db,service,vault,"https://issuer.test","sso_authsystem_v2");
        String ref="ops-check", group="ops-group", other="ops-other";
        ops.group("admin",group,"第一组",true);
        ops.group("admin",other,"第二组",true);
        var ca=vault.open("fixture",vault.create("fixture")).issuer();
        ops.saveNode("admin",ref,"线路一","example.test",9090,"example.test",NodeCatalog.pem(ca.getEncoded()),true);
        ops.membership("admin",group,List.of(ref));
        ops.membership("admin",other,List.of(ref));
        var key=device().getPublic().getEncoded();var end=Instant.now().plusSeconds(120);
        String deviceId=UUID.randomUUID().toString();
        assertTrue(catalog.issue(group,deviceId,key,end).isEmpty());
        ops.nodeAction("admin",ref,"on");
        assertEquals(ref,catalog.issue(group,deviceId,key,end).get(0).id());
        assertEquals(ref,catalog.issue(other,deviceId,key,end).get(0).id());
        ops.membership("admin",other,List.of());
        assertTrue(catalog.issue(other,deviceId,key,end).isEmpty());
        assertThrows(ConsoleService.Problem.class,()->ops.removeGroup("admin",group));
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(ops.bundle("admin",ref)))) {
            Set<String> files=new HashSet<>();
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()){
                files.add(entry.getName());
                assertFalse(new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).contains("PRIVATE KEY"));
            }
            assertEquals(Set.of("device-ca.crt","subscription-frame.key","接入说明.txt"),files);
        }
        ops.nodeAction("admin",ref,"off");
        assertTrue(catalog.issue(group,deviceId,key,end).isEmpty());
        ops.nodeAction("admin",ref,"delete");
        ops.removeGroup("admin",group);
    }

    static KeyPair device() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    static String sign(KeyPair key, String text) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key.getPrivate());
        signer.update(text.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    EnrollmentService.Pair start(KeyPair key) {
        return service.start("Test desktop", Base64.getEncoder().encodeToString(key.getPublic().getEncoded()));
    }

    void allow(String owner) {
        service.grant(owner, new EnrollmentService.Grant("group-a", Instant.now().getEpochSecond()+600, 1));
    }

    @Test void pairingManifestReplayAndUnbind() throws Exception {
        String owner = "owner-a";
        KeyPair device = device(), other = device();
        var pair = start(device);
        String poll = "simpleplane-pair-v1\n" + pair.deviceSecret();
        assertFalse(pair.verificationUri().contains(pair.deviceSecret()));
        assertEquals("pending", service.poll(pair.deviceSecret(), sign(device, poll)).status());
        assertThrows(ResponseStatusException.class, () -> service.decide(owner, pair.userCode(), true));
        allow(owner);
        service.decide(owner, pair.userCode(), true);
        assertThrows(ResponseStatusException.class, () -> service.poll(pair.deviceSecret(), sign(other, poll)));
        UUID id = UUID.fromString(service.poll(pair.deviceSecret(), sign(device, poll)).deviceId());
        assertNull(service.poll(pair.deviceSecret(), sign(device, poll)).deviceId());
        String nonce = api.challenge(id).challenge();
        String tlsKey = Base64.getEncoder().encodeToString(device().getPublic().getEncoded());
        String message = "simpleplane-manifest-v1\n" + id + "\n" + nonce + "\n" + tlsKey;
        assertThrows(ResponseStatusException.class, () -> api.manifest(id, new DeviceController.Proof(nonce, tlsKey, sign(other, message))));
        String swappedKey = Base64.getEncoder().encodeToString(other.getPublic().getEncoded());
        assertThrows(ResponseStatusException.class, () -> api.manifest(id, new DeviceController.Proof(nonce, swappedKey, sign(device, message))));
        var proof = new DeviceController.Proof(nonce, tlsKey, sign(device, message));
        var manifest = api.manifest(id, proof);
        assertTrue(manifest.nodes().isEmpty());
        assertTrue(manifest.expiresAt() <= Instant.now().getEpochSecond()+300);
        assertThrows(ResponseStatusException.class, () -> api.manifest(id, proof));
        assertThrows(ResponseStatusException.class, () -> service.unbind("someone-else", id.toString()));
        assertEquals(1, service.devices(owner).size());
        assertTrue(service.devices("someone-else").isEmpty());
        service.unbind(owner, id.toString());
        assertThrows(ResponseStatusException.class, () -> api.manifest(id, proof));
    }

    @Test void concurrentApprovalsShareOneSlot() throws Exception {
        String owner = "concurrent";
        allow(owner);
        var one = start(device());
        var two = start(device());
        var barrier = new CyclicBarrier(2);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (var pair : List.of(one, two)) {
                results.add(threads.submit(() -> {
                    barrier.await(2, TimeUnit.SECONDS);
                    try { service.decide(owner, pair.userCode(), true); return 200; }
                    catch (ResponseStatusException rejected) { return rejected.getStatusCode().value(); }
                }));
            }
            assertEquals(Set.of(200,409), Set.of(results.get(0).get(5,TimeUnit.SECONDS), results.get(1).get(5,TimeUnit.SECONDS)));
            assertEquals(1, service.devices(owner).size());
        } finally { threads.shutdownNow(); }
    }

    @Test void deniedAndExpiredPairingsCannotBeRedeemed() throws Exception {
        var key = device();
        var pair = start(key);
        service.decide("test",pair.userCode(),false);
        assertEquals("denied", service.poll(pair.deviceSecret(),sign(key,"simpleplane-pair-v1\n"+pair.deviceSecret())).status());
        assertThrows(ResponseStatusException.class, () -> service.decide("test",pair.userCode(),true));
        db.update("UPDATE subscription_pairing SET expires_at=0 WHERE user_code=?", pair.userCode());
        assertThrows(ResponseStatusException.class, () -> service.view(pair.userCode()));
    }
}
