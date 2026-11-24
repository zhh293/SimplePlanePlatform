package com.proxy.subscription;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EnrollmentService {
    public record Pair(String userCode, String deviceSecret, long expiresAt, String verificationUri) {}
    public record RequestView(String name, String userCode, String status) {}
    public record Device(String id, String name) {}
    public record Grant(String group, long validUntil, int deviceLimit) {}
    public record Poll(String status, String deviceId) {}
    private record Pending(String name, byte[] key, long expiresAt, String status, String deviceId) {}

    private final JdbcTemplate db;
    private final String origin;
    private final String issuer;

    EnrollmentService(JdbcTemplate db, @Value("${subscription.origin}") String origin,
                      @Value("${sso.client.issuer}") String issuer) {
        this.db = db;
        this.origin = origin;
        this.issuer = issuer;
    }

    private byte[] account(String subject) {
        if (subject == null || subject.isBlank() || subject.length() > 255 || subject.indexOf(0) >= 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return DeviceKeys.digest(issuer + "\0" + subject);
    }

    public void grant(String subject, Grant grant) {
        if (grant.group() == null || !grant.group().matches("[A-Za-z0-9_-]{1,96}")
                || grant.validUntil() < 0 || grant.deviceLimit() < 0 || grant.deviceLimit() > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        db.update("""
            INSERT INTO subscription_account(account_id,group_id,valid_until,device_limit) VALUES(?,?,?,?)
            ON DUPLICATE KEY UPDATE group_id=VALUES(group_id), valid_until=VALUES(valid_until), device_limit=VALUES(device_limit)
            """, account(subject), grant.group(), grant.validUntil(), grant.deviceLimit());
    }

    public Pair start(String name, String encodedKey) {
        if (name == null || name.isBlank() || name.length() > 80 || name.chars().anyMatch(Character::isISOControl))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        byte[] key = DeviceKeys.parsePublicKey(encodedKey);
        String code = DeviceKeys.userCode(), secret = DeviceKeys.randomSecret();
        long expiry = Instant.now().getEpochSecond() + 300;
        db.update("""
            INSERT INTO subscription_pairing(user_code,secret_digest,public_key,display_name,expires_at,status)
            VALUES(?,?,?,?,?,'pending')
            """, code, DeviceKeys.digest(secret), key, name, expiry);
        return new Pair(code, secret, expiry, origin + "/account/pair/" + code);
    }

    private Pending pairing(String predicate, Object value, boolean lock) {
        var result = db.query("SELECT display_name,public_key,expires_at,status,device_id FROM subscription_pairing WHERE "
                + predicate + (lock ? " FOR UPDATE" : ""), (rs, n) -> new Pending(rs.getString(1),
                rs.getBytes(2), rs.getLong(3), rs.getString(4), rs.getString(5)), value);
        if (result.isEmpty() || result.get(0).expiresAt() <= Instant.now().getEpochSecond())
            throw new ResponseStatusException(HttpStatus.GONE);
        return result.get(0);
    }

    public RequestView view(String code) {
        Pending pending = pairing("user_code=?", code, false);
        return new RequestView(pending.name(), code, pending.status());
    }

    @Transactional
    public void decide(String subject, String code, boolean approve) {
        Pending pending = pairing("user_code=?", code, true);
        if (!"pending".equals(pending.status())) throw new ResponseStatusException(HttpStatus.CONFLICT);
        if (!approve) {
            db.update("UPDATE subscription_pairing SET status='denied' WHERE user_code=?", code);
            return;
        }
        byte[] owner = account(subject);
        Grant grant = activeGrant(owner);
        byte[] digest = DeviceKeys.digest(pending.key());
        List<String> existing = db.queryForList(
                "SELECT device_id FROM subscription_device WHERE account_id=? AND key_digest=? FOR UPDATE",
                String.class, owner, digest);
        String deviceId;
        if (existing.isEmpty()) {
            int count = db.queryForObject("SELECT COUNT(*) FROM subscription_device WHERE account_id=?", Integer.class, owner);
            if (count >= grant.deviceLimit()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Device limit reached");
            deviceId = UUID.randomUUID().toString();
            db.update("""
                INSERT INTO subscription_device(device_id,account_id,display_name,key_digest,public_key)
                VALUES(?,?,?,?,?)
                """, deviceId, owner, pending.name(), digest, pending.key());
        } else {
            deviceId = existing.get(0);
        }
        db.update("UPDATE subscription_pairing SET status='approved',device_id=? WHERE user_code=?", deviceId, code);
    }

    Grant activeGrant(byte[] owner) {
        var rows = db.query("SELECT group_id,valid_until,device_limit FROM subscription_account WHERE account_id=? FOR UPDATE",
                (rs,n) -> new Grant(rs.getString(1), rs.getLong(2), rs.getInt(3)), owner);
        if (rows.isEmpty() || rows.get(0).validUntil() <= Instant.now().getEpochSecond() || rows.get(0).deviceLimit() == 0)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No active subscription");
        return rows.get(0);
    }

    @Transactional
    public Poll poll(String secret, String signature) {
        if (secret == null || secret.length() != 43) throw new ResponseStatusException(HttpStatus.GONE);
        byte[] digest = DeviceKeys.digest(secret);
        Pending request = pairing("secret_digest=?", digest, true);
        DeviceKeys.verify(request.key(), "simpleplane-pair-v1\n" + secret, signature);
        if (!"approved".equals(request.status())) return new Poll(request.status(), null);
        db.update("UPDATE subscription_pairing SET status='redeemed' WHERE secret_digest=?", digest);
        return new Poll("approved", request.deviceId());
    }

    public List<Device> devices(String subject) {
        return db.query("SELECT device_id,display_name FROM subscription_device WHERE account_id=? ORDER BY device_id",
                (rs,n) -> new Device(rs.getString(1), rs.getString(2)), account(subject));
    }

    public void unbind(String subject, String deviceId) {
        if (db.update("DELETE FROM subscription_device WHERE account_id=? AND device_id=?", account(subject), deviceId) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    @Scheduled(fixedDelay = 60000)
    public void expire() {
        long now = Instant.now().getEpochSecond();
        db.update("DELETE FROM subscription_pairing WHERE expires_at <= ? LIMIT 1000", now);
        db.update("DELETE FROM subscription_challenge WHERE expires_at <= ? LIMIT 1000", now);
    }
}
