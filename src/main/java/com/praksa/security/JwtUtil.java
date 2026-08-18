package com.praksa.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Mints and verifies JWTs.
 *
 * <p>Key selection (BUG-15) is deliberately explicit so that enabling external
 * authentication can never silently reuse the local/development signing key:
 * <ul>
 *   <li><b>Local mode</b> ({@code auth.external-enabled=false}, the default) —
 *       tokens are signed and verified with the application's own local signing
 *       key ({@code jwt.secret} / {@code JWT_SECRET}).</li>
 *   <li><b>External mode</b> ({@code auth.external-enabled=true}) — tokens are
 *       signed and verified with a SEPARATE, explicitly-configured external key
 *       ({@code auth.external-jwt-secret} / {@code EXTERNAL_JWT_SECRET}). There is
 *       no fallback to the local key: if external auth is enabled without an
 *       external key, startup fails fast (see {@link #validateKeyConfiguration()}).</li>
 * </ul>
 *
 * Neither key ships an inline production default; both come from the environment
 * (or the git-ignored {@code application-local.properties} for local dev).
 */
@Component
public class JwtUtil {

    /** Local application signing key. Required — no inline default in application.properties. */
    @Value("${jwt.secret}")
    private String localSecret;

    /** External-provider signing key. Empty unless external auth is configured. */
    @Value("${auth.external-jwt-secret:}")
    private String externalSecret;

    @Value("${auth.external-enabled:false}")
    private boolean externalAuthEnabled;

    @Value("${jwt.expiration-ms}")
    private long expirationMs;

    /**
     * Fail-fast configuration guard. Runs at startup. Guarantees that:
     * <ul>
     *   <li>a local signing key is always present (production must supply
     *       {@code JWT_SECRET}; an unresolved placeholder already fails earlier), and</li>
     *   <li>enabling external auth without its own key is rejected — it must NEVER
     *       fall back to the local/development key.</li>
     * </ul>
     */
    @PostConstruct
    void validateKeyConfiguration() {
        if (localSecret == null || localSecret.isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret is not configured. Set the JWT_SECRET environment variable "
                    + "(or jwt.secret in the git-ignored application-local.properties for local dev). "
                    + "The application refuses to start with a missing/insecure signing key.");
        }
        if (externalAuthEnabled && (externalSecret == null || externalSecret.isBlank())) {
            throw new IllegalStateException(
                    "auth.external-enabled=true but auth.external-jwt-secret is not configured. "
                    + "Set the EXTERNAL_JWT_SECRET environment variable. External authentication "
                    + "must use its own explicitly-configured signing key and must NEVER fall back "
                    + "to the local/development JWT secret.");
        }
    }

    /**
     * The active HMAC key. Local mode → local key; external mode → external key.
     * Used for both signing (tokens we mint) and verification, so each mode is
     * self-consistent and external mode never touches the local key.
     */
    private SecretKey getKey() {
        String secret = externalAuthEnabled ? externalSecret : localSecret;
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String email, String role) {
        return Jwts.builder()
                .subject(email)
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(getKey())
                .compact();
    }

    public String extractEmail(String token) {
        return getClaims(token).getSubject();
    }

    public String extractRole(String token) {
        return getClaims(token).get("role", String.class);
    }

    /** Optional claim — present only for tokens issued by an external provider. */
    public String extractName(String token) {
        return getClaims(token).get("name", String.class);
    }

    /** Optional claim — present only for students. */
    public String extractIndexNumber(String token) {
        return getClaims(token).get("index_number", String.class);
    }

    public boolean isTokenValid(String token) {
        try {
            getClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(getKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
