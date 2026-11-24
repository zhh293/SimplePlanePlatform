package com.proxy.subscription;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class NodeMaterial {
    record Keys(PrivateKey signer, X509Certificate issuer, String frame) {
        @Override public String toString() { return "node credentials"; }
    }
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec vault;

    NodeMaterial(@Value("${subscription.challenge-key}") String secret) throws GeneralSecurityException {
        byte[] master=Base64.getDecoder().decode(secret);
        if (master.length!=32) throw new IllegalArgumentException("Invalid master key");
        var derivation=javax.crypto.Mac.getInstance("HmacSHA256");
        derivation.init(new SecretKeySpec(master,"HmacSHA256"));
        vault=new SecretKeySpec(derivation.doFinal("simpleplane/node-vault/v1".getBytes(StandardCharsets.US_ASCII)),"AES");
    }

    static X509Certificate certificate(byte[] encoded) throws GeneralSecurityException {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(encoded));
    }

    byte[] create(String id) throws Exception {
        if (vault == null) throw new IllegalStateException("Node vault is not configured");
        KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair=generator.generateKeyPair();
        Instant now=Instant.now();
        X500Name name=new X500Name("CN=SimplePlane-"+id);
        var cert=new JcaX509v3CertificateBuilder(name,new BigInteger(158,RANDOM).setBit(158),
                Date.from(now.minusSeconds(60)),Date.from(now.plusSeconds(315360000)),name,pair.getPublic());
        cert.addExtension(Extension.basicConstraints,true,new BasicConstraints(0));
        cert.addExtension(Extension.keyUsage,true,new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        var ca=new JcaX509CertificateConverter().getCertificate(cert.build(new JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate())));
        byte[] frame=new byte[16];
        RANDOM.nextBytes(frame);
        return seal(id,new Keys(pair.getPrivate(),ca,HexFormat.of().formatHex(frame)));
    }

    byte[] seal(String id,Keys keys) throws GeneralSecurityException {
        byte[] nonce=new byte[12];
        RANDOM.nextBytes(nonce);
        var b64=Base64.getEncoder();
        String payload=b64.encodeToString(keys.signer().getEncoded())+"\n"+b64.encodeToString(keys.issuer().getEncoded())+"\n"+keys.frame();
        Cipher cipher=cipher(Cipher.ENCRYPT_MODE,id,nonce);
        byte[] encrypted=cipher.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
        byte[] out=Arrays.copyOf(nonce,nonce.length+encrypted.length);
        System.arraycopy(encrypted,0,out,nonce.length,encrypted.length);
        return out;
    }

    Keys open(String id, byte[] sealed) throws GeneralSecurityException {
        if (sealed==null || sealed.length<29 || vault==null) throw new GeneralSecurityException("Missing node material");
        byte[] payload=cipher(Cipher.DECRYPT_MODE,id,Arrays.copyOf(sealed,12)).doFinal(sealed,12,sealed.length-12);
        String[] fields=new String(payload,StandardCharsets.US_ASCII).split("\n");
        var b64=Base64.getDecoder();
        PrivateKey key=KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(b64.decode(fields[0])));
        return new Keys(key,certificate(b64.decode(fields[1])),fields[2]);
    }

    private Cipher cipher(int mode,String id,byte[] nonce) throws GeneralSecurityException {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode,vault,new GCMParameterSpec(128,nonce));
        cipher.updateAAD(id.getBytes(StandardCharsets.US_ASCII));
        return cipher;
    }
}
