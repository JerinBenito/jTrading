package com.jtrade.login_service.config;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.*;
import java.util.Base64;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

@Configuration
@ConfigurationProperties(prefix = "jwt")
public class JwtConfig {

    private Resource privateKey;
    private Resource publicKey;

    public void setPrivateKey(Resource privateKey) {
        this.privateKey = privateKey;
    }

    public void setPublicKey(Resource publicKey) {
        this.publicKey = publicKey;
    }

    public PrivateKey getPrivateKey() throws Exception {
        try (InputStream is = privateKey.getInputStream()) {
            String key = new String(is.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("-----BEGIN (.*)-----", "")
                    .replaceAll("-----END (.*)-----", "")
                    .replaceAll("\\s+", "");

            byte[] keyBytes = Base64.getDecoder().decode(key);

            try {
                // Try PKCS#8 first
                PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
                KeyFactory kf = KeyFactory.getInstance("RSA");
                return kf.generatePrivate(spec);
            } catch (InvalidKeySpecException e) {
                // Try PKCS#1
                return readPkcs1PrivateKey(keyBytes);
            }
        }
    }

    private PrivateKey readPkcs1PrivateKey(byte[] keyBytes) throws Exception {
        // Add PKCS#8 header for PKCS#1 keys
        String pkcs8Header = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBK";
        byte[] pkcs8bytes = new byte[pkcs8Header.length() + keyBytes.length];
        System.arraycopy(keyBytes, 0, pkcs8bytes, 0, keyBytes.length);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(pkcs8bytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(spec);
    }

    public PublicKey getPublicKey() throws Exception {
        try (InputStream is = publicKey.getInputStream()) {
            String key = new String(is.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("-----BEGIN (.*)-----", "")
                    .replaceAll("-----END (.*)-----", "")
                    .replaceAll("\\s+", "");
            byte[] keyBytes = Base64.getDecoder().decode(key);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePublic(spec);
        }
    }
}
