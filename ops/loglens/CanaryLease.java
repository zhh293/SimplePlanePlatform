import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Encodes a short-lived test credential for the existing SimplePlane client. */
final class CanaryLease {
    public static void main(String[] args) throws Exception {
        Path folder = Path.of(args[0]);
        byte[] privateKey = Files.readAllBytes(folder.resolve("key.der"));
        byte[] certificate = Files.readAllBytes(folder.resolve("cert.der"));
        byte[] clear = ByteBuffer.allocate(2 + privateKey.length + certificate.length)
                .putShort((short) privateKey.length).put(privateKey).put(certificate).array();
        byte[] wrapping = Base64.getDecoder().decode(System.getenv("SIMPLEPLANE_LEASE_KEY"));
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(wrapping, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD("canary".getBytes(StandardCharsets.US_ASCII));
        byte[] encrypted = cipher.doFinal(clear);
        byte[] value = ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
        Files.writeString(folder.resolve("leases.properties"), "canary=" + Base64.getEncoder().encodeToString(value));
    }
}
