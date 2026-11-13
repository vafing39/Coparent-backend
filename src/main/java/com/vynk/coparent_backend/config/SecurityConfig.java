package com.vynk.coparent_backend.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.password.CompromisedPasswordChecker;
import org.springframework.security.authentication.password.CompromisedPasswordDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.password.HaveIBeenPwnedRestApiPasswordChecker;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
public class SecurityConfig {

    private static final int MIN_SECRET_BYTES = 32; // HS256 needs a key of at least 256 bits

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // stateless bearer-token API, no cookies
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/email/resend", "/api/v1/auth/email/verify")
                        .authenticated()
                        .requestMatchers("/api/v1/auth/**", "/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/invitations/*").permitAll() // aperçu avant acceptation
                        // Lien d'invitation ouvert dans l'app : fichiers de vérification et page web d'atterrissage.
                        .requestMatchers(HttpMethod.GET, "/.well-known/apple-app-site-association",
                                "/.well-known/assetlinks.json", "/invitation").permitAll()
                        // WebSocket de la messagerie : le jeton est vérifié au CONNECT STOMP (StompAuthInterceptor).
                        .requestMatchers("/ws").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    SecretKey jwtSigningKey(AuthProperties properties) {
        byte[] secret = properties.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("app.auth.jwt-secret must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey) {
        return NimbusJwtDecoder.withSecretKey(jwtSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
    }

    /** Delegating encoder: bcrypt today, and transparent upgrades later (hashes are prefixed with {bcrypt}). */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Rejects passwords known as compromised. Uses the Have I Been Pwned k-anonymity API: only the first
     * 5 characters of the password's SHA-1 hash leave the server.
     */
    @Bean
    CompromisedPasswordChecker compromisedPasswordChecker(AuthProperties properties) {
        if (!properties.checkCompromisedPasswords()) {
            return password -> new CompromisedPasswordDecision(false);
        }
        return new HaveIBeenPwnedRestApiPasswordChecker();
    }
}
