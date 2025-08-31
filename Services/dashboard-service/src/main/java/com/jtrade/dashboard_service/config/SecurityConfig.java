package com.jtrade.dashboard_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.interfaces.RSAPublicKey;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.stream.Collectors;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/dashboard/secure/info").permitAll() // actuator open
                .anyRequest().authenticated()                // all other endpoints require JWT
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt());

        return http.build();
    }

    // Load the public key generated in login-service
    @Bean
    public JwtDecoder jwtDecoder() throws Exception {
        String key = Files.lines(Paths.get("src/main/resources/keys/public_key.pem"))
                .filter(line -> !line.startsWith("-----"))
                .collect(Collectors.joining());

        byte[] decoded = java.util.Base64.getDecoder().decode(key);
        java.security.spec.X509EncodedKeySpec spec = new java.security.spec.X509EncodedKeySpec(decoded);
        java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
        RSAPublicKey publicKey = (RSAPublicKey) kf.generatePublic(spec);

        return NimbusJwtDecoder.withPublicKey(publicKey).build();
    }
}
