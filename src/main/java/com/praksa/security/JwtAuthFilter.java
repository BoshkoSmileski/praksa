package com.praksa.security;

import com.praksa.model.User;
import com.praksa.model.enums.Role;
import com.praksa.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Reads the Bearer token, validates it, and populates SecurityContext.
 *
 * Behavior depends on `auth.external-enabled`:
 *   false (default)  — only known users are accepted; unknown email = 401.
 *   true             — when the token references a user we don't yet have,
 *                      we create their record from the JWT claims (email,
 *                      name, role, optional index_number). This is the
 *                      "auto-provisioning" pattern for external auth integration.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${auth.external-enabled:false}")
    private boolean externalAuthEnabled;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        if (!jwtUtil.isTokenValid(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        String email = jwtUtil.extractEmail(token);
        if (email == null || SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            UserDetails userDetails = userDetailsService.loadUserByUsername(email);
            setAuthentication(request, userDetails);
        } catch (UsernameNotFoundException ex) {
            if (externalAuthEnabled) {
                // Auto-provision from JWT claims
                User provisioned = autoProvision(email, token);
                UserDetails userDetails = userDetailsService.loadUserByUsername(provisioned.getEmail());
                setAuthentication(request, userDetails);
                log.info("Auto-provisioned user from external token: {} (role {})", email, provisioned.getRole());
            } else {
                // Local mode + unknown user = unauthenticated; just continue with no auth.
                log.debug("Unknown user '{}' in token; ignoring (external auth disabled)", email);
            }
        }

        filterChain.doFilter(request, response);
    }

    private void setAuthentication(HttpServletRequest request, UserDetails userDetails) {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /**
     * Create a User row from the JWT's claims. The external system is the source
     * of truth for identity, so we just snapshot what the token says.
     *
     * The password hash is set to a random value because the user will never log in
     * with a password locally — they always come via the external system.
     */
    private User autoProvision(String email, String token) {
        String roleClaim = jwtUtil.extractRole(token);
        String name = jwtUtil.extractName(token);
        String indexNumber = jwtUtil.extractIndexNumber(token);

        if (roleClaim == null) {
            throw new IllegalStateException("Cannot auto-provision: token has no 'role' claim");
        }

        Role role;
        try {
            role = Role.valueOf(roleClaim);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Cannot auto-provision: unknown role '" + roleClaim + "'");
        }

        User user = User.builder()
                .email(email)
                .fullName(name != null ? name : email)
                .role(role)
                .indexNumber(role == Role.STUDENT ? indexNumber : null)
                // Unguessable random hash — local password login is not used for this user
                .passwordHash(passwordEncoder.encode(UUID.randomUUID().toString()))
                .build();

        return userRepository.save(user);
    }
}
