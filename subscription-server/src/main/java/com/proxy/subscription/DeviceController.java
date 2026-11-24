package com.proxy.subscription;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/device")
public class DeviceController {
    public record Start(String name, String publicKey) {}
    public record Poll(String deviceSecret, String signature) {}
    public record Challenge(String challenge) {}
    public record Proof(String challenge, String tlsPublicKey, String signature) {}
    public record Manifest(int version, long expiresAt, List<NodeCatalog.IssuedNode> nodes) {}
    private final EnrollmentService enrollment;
    private final NodeCatalog catalog;
    private final JdbcTemplate db;
    private final SecretKeySpec challengeKey;

    DeviceController(EnrollmentService enrollment, NodeCatalog catalog, JdbcTemplate db,
                     @Value("${subscription.challenge-key}") String key) {
        this.enrollment = enrollment;
        this.catalog = catalog;
        this.db = db;
        byte[] bytes = Base64.getDecoder().decode(key);
        if (bytes.length != 32) throw new IllegalArgumentException("Expected 32-byte subscription challenge key");
        challengeKey = new SecretKeySpec(bytes, "HmacSHA256");
    }

    @PostMapping("/pair")
    EnrollmentService.Pair start(@RequestBody Start input) {
        return enrollment.start(input.name(), input.publicKey());
    }

    @PostMapping("/poll")
    EnrollmentService.Poll poll(@RequestBody Poll input) {
        return enrollment.poll(input.deviceSecret(), input.signature());
    }

    @PostMapping("/{id}/challenge")
    Challenge challenge(@PathVariable UUID id) {
        String payload = (Instant.now().getEpochSecond() + 60) + "." + DeviceKeys.randomSecret();
        return new Challenge(payload + "." + mac(id, payload));
    }

    @Transactional
    @PostMapping("/{id}/manifest")
    public Manifest manifest(@PathVariable UUID id, @RequestBody Proof proof) {
        String challenge = proof.challenge();
        if (challenge == null || !challenge.matches("[0-9]{10}\\.[A-Za-z0-9_-]{43}\\.[A-Za-z0-9_-]{43}"))
            throw forbidden();
        int end = challenge.lastIndexOf('.');
        long now = Instant.now().getEpochSecond();
        long until = Long.parseLong(challenge.substring(0, challenge.indexOf('.')));
        if (until <= now || !MessageDigest.isEqual(mac(id, challenge.substring(0, end)).getBytes(StandardCharsets.US_ASCII),
                challenge.substring(end + 1).getBytes(StandardCharsets.US_ASCII))) throw forbidden();
        var owners = db.queryForList("SELECT account_id FROM subscription_device WHERE device_id=?", byte[].class, id.toString());
        if (owners.isEmpty()) throw forbidden();
        var grant = enrollment.activeGrant(owners.get(0));
        var keys = db.queryForList("SELECT public_key FROM subscription_device WHERE device_id=? AND account_id=? FOR UPDATE",
                byte[].class, id.toString(), owners.get(0));
        if (keys.isEmpty()) throw forbidden();
        byte[] tlsKey = DeviceKeys.parsePublicKey(proof.tlsPublicKey());
        String encoded = Base64.getEncoder().encodeToString(tlsKey);
        DeviceKeys.verify(keys.get(0), "simpleplane-manifest-v1\n" + id + "\n" + challenge + "\n" + encoded, proof.signature());
        try {
            db.update("INSERT INTO subscription_challenge(nonce_digest,device_id,expires_at) VALUES(?,?,?)",
                    DeviceKeys.digest(challenge), id.toString(), until);
        } catch (DuplicateKeyException alreadyRedeemed) {
            throw forbidden();
        }
        long expiry = Math.min(grant.validUntil(), now + 300);
        return new Manifest(1, expiry, catalog.issue(grant.group(), id.toString(), tlsKey, Instant.ofEpochSecond(expiry)));
    }

    private String mac(UUID id, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(challengeKey);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal((id + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static ResponseStatusException forbidden() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Device authorization rejected");
    }
}
