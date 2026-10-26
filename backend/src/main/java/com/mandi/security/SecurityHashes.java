package com.mandi.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * Keyed hashes (HMAC-SHA256) of identifiers that must not be stored in the clear: an email typed at the login form (it
 * may belong to a stranger) and a client address. The key comes from the configuration ({@code mandi.security.hmac-key}),
 * so the values are comparable across instances but cannot be reversed by guessing candidates without the key.
 */
@Component
public class SecurityHashes {

    private final byte[] key;

    public SecurityHashes(SecurityProps props) {
        String k = props.hmacKey();
        if (k == null || k.length() < 32) {
            throw new IllegalStateException("mandi.security.hmac-key must be set to at least 32 characters");
        }
        this.key = k.getBytes(StandardCharsets.UTF_8);
    }

    /** 32 bytes. The scope separates uses so an email key can never equal an address key. */
    public byte[] hmac(String scope, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(scope.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
