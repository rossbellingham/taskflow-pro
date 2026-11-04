package com.taskflow.security;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

@Service
public class JwtService {

    private final SecretKey key;
    private final Duration lifetime;

    public JwtService(@Value("${app.jwt.secret}") String base64Secret,
                      @Value("${app.jwt.expiration-minutes:15}") long expirationMinutes) {
        byte[] bytes = Decoders.BASE64.decode(base64Secret);
        if (bytes.length < 32) {
            // Fail at startup rather than run with a guessable signing key
            throw new IllegalStateException("app.jwt.secret must be a base64 value of at least 32 bytes (256 bits)");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.lifetime = Duration.ofMinutes(expirationMinutes);
    }

    public String generateToken(UserDetails user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getUsername())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(lifetime)))
                .signWith(key)
                .compact();
    }

    /** Returns the username if the token is correctly signed and unexpired, otherwise empty. */
    public Optional<String> validateAndGetUsername(String token) {
        try {
            return Optional.ofNullable(
                    Jwts.parser().verifyWith(key).build()
                            .parseSignedClaims(token)
                            .getPayload()
                            .getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public long getLifetimeSeconds() {
        return lifetime.toSeconds();
    }
}
