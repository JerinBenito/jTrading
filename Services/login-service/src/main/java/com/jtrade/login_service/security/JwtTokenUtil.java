package com.jtrade.login_service.security;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Date;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.jtrade.login_service.config.JwtConfig;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.JwtException;

@Component
public class JwtTokenUtil {

    @Autowired
    private JwtConfig jwtConfig;

    private static final long JWT_EXPIRATION = 24 * 60 * 60 * 1000; // 1 day

    public String generateToken(String username) {
        try {
            PrivateKey privateKey = jwtConfig.getPrivateKey();
            return Jwts.builder()
                    .setSubject(username)
                    .setIssuedAt(new Date())
                    .setExpiration(new Date(System.currentTimeMillis() + JWT_EXPIRATION))
                    .signWith(privateKey, SignatureAlgorithm.RS256)
                    .compact();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate JWT token", e);
        }
    }

    public boolean validateToken(String token) {
        try {
            PublicKey publicKey = jwtConfig.getPublicKey();
            Jwts.parserBuilder()
                .setSigningKey(publicKey)
                .build()
                .parseClaimsJws(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            // invalid token
            return false;
        } catch (Exception e) {
            throw new RuntimeException("Failed to validate JWT token", e);
        }
    }

    public String getUsernameFromToken(String token) {
        try {
            PublicKey publicKey = jwtConfig.getPublicKey();
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(publicKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            return claims.getSubject();
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract username from JWT", e);
        }
    }
}
