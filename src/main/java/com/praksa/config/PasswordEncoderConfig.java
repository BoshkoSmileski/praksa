package com.praksa.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * PasswordEncoder lives in its own config class so it can be injected into
 * JwtAuthFilter without creating a cycle through SecurityConfig.
 *
 *   SecurityConfig → JwtAuthFilter → PasswordEncoder (via this config)
 *                                      ↑
 *                              (no path back to SecurityConfig)
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
