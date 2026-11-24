package com.proxy.transport.http3;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Optional observability fields from a peer certificate already validated by TLS. */
public final class DeviceIdentity {
    private static final AttributeKey<DeviceIdentity> KEY = AttributeKey.valueOf(DeviceIdentity.class, "authenticated");
    private static final String ACCOUNT_URI = "https://simpleplane.invalid/account/";
    private static final Pattern ACCOUNT = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SUBJECT = Pattern.compile("CN=([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})");

    private final String deviceId;
    private final String accountId;

    private DeviceIdentity(String deviceId, String accountId) {
        this.deviceId = deviceId;
        this.accountId = accountId;
    }

    public String getDeviceId() { return deviceId; }
    public String getAccountId() { return accountId; }

    static void record(Channel authenticated, X509Certificate certificate) {
        Matcher subject = SUBJECT.matcher(certificate.getSubjectX500Principal().getName());
        if (!subject.matches()) return;
        String account = null;
        try {
            Collection<List<?>> names = certificate.getSubjectAlternativeNames();
            if (names != null) {
                for (List<?> name : names) {
                    if (!Integer.valueOf(6).equals(name.get(0)) || !(name.get(1) instanceof String)) continue;
                    String uri = (String) name.get(1);
                    if (!uri.startsWith(ACCOUNT_URI)) continue;
                    String value = uri.substring(ACCOUNT_URI.length());
                    if (!ACCOUNT.matcher(value).matches() || (account != null && !account.equals(value))) {
                        account = null;
                        break;
                    }
                    account = value;
                }
            }
        } catch (CertificateParsingException ignored) {
            // Optional metadata must not change whether an otherwise valid peer is admitted.
        }
        authenticated.attr(KEY).set(new DeviceIdentity(subject.group(1), account));
    }

    public static DeviceIdentity forStream(Channel stream) {
        Channel connection = stream.parent();
        return connection != null && connection.hasAttr(KEY) ? connection.attr(KEY).get() : null;
    }
}
