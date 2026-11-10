package com.dataextraction.saas.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.http.HttpMethod;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import com.dataextraction.saas.organization.OrganizationStore;
import com.dataextraction.saas.organization.WorkspaceOrganization;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }

    @Bean CsrfTokenRepository csrfTokenRepository(@Value("${app.security.cookie-secure:false}") boolean secureCookie) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.path("/").sameSite("Lax").secure(secureCookie));
        return repository;
    }

    @Bean AuthenticationManager authenticationManager(AccountUserDetailsService users, PasswordEncoder passwords) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwords);
        return new ProviderManager(provider);
    }

    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SecurityContextRepository contexts,
                                                   CsrfTokenRepository csrfTokens,
                                                   AccountStore accounts) throws Exception {
        http
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .securityContext(context -> context.requireExplicitSave(true).securityContextRepository(contexts))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.migrateSession()))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/").permitAll()
                        .requestMatchers("/actuator/health", "/api/v1/auth/csrf", "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"status\":401,\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"Sign in is required\"}");
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(403);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"status\":403,\"code\":\"ACCESS_DENIED\",\"message\":\"You are not allowed to access this resource\"}");
                        }))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .addFilterBefore(new AccountAccessFilter(accounts, contexts), AuthorizationFilter.class);
        return http.build();
    }

    @Bean ApplicationRunner demoAccounts(AccountStore accounts, OrganizationStore organizations, JdbcTemplate jdbc, PasswordEncoder encoder,
                                         @Value("${app.security.seed-demo-users:false}") boolean enabled) {
        return args -> {
            if (!enabled) return;
            UUID systemOrganization = UUID.fromString("00000000-0000-0000-0000-000000000000");
            UUID adminOrganization = UUID.fromString("00000000-0000-0000-0000-000000000001");
            seedOrganization(organizations, systemOrganization, "ExtractIQ System Workspace");
            seedOrganization(organizations, adminOrganization, "ExtractIQ Admin Workspace");
            seed(accounts, encoder, "superadmin@extractiq.local", "SuperAdmin@ChangeMe123!", "ExtractIQ Super Admin", "SUPER_ADMIN", systemOrganization);
            seed(accounts, encoder, "admin@extractiq.local", "Admin@ChangeMe123!", "ExtractIQ Admin", "ADMIN", adminOrganization);
            UUID legacyUserOrganization = UUID.fromString("00000000-0000-0000-0000-000000000002");
            moveLegacyDemoWorkspace(accounts, jdbc, legacyUserOrganization, adminOrganization);
            seed(accounts, encoder, "user@extractiq.local", "User@ChangeMe123!", "ExtractIQ User", "USER", adminOrganization);
        };
    }

    private void moveLegacyDemoWorkspace(AccountStore accounts, JdbcTemplate jdbc, UUID legacyOrganizationId, UUID adminOrganizationId) {
        String from = legacyOrganizationId.toString();
        String to = adminOrganizationId.toString();
        for (UserAccount account : accounts.all().stream().filter(user -> user.organizationId().equals(legacyOrganizationId)).toList()) {
            accounts.save(new UserAccount(account.id(), adminOrganizationId, account.email(), account.passwordHash(), account.displayName(),
                    account.role(), account.enabled(), account.protectedAdmin(), account.createdAt()));
        }
        for (String table : java.util.List.of("documents", "extraction_jobs", "extraction_results", "exports", "audit_events")) {
            jdbc.update("UPDATE " + table + " SET organization_id = ? WHERE organization_id = ?", to, from);
        }
        jdbc.update("DELETE FROM organizations WHERE organization_id = ? AND NOT EXISTS (SELECT 1 FROM accounts WHERE organization_id = ?)", from, from);
    }

    private void seedOrganization(OrganizationStore organizations, UUID id, String name) {
        organizations.findById(id).orElseGet(() -> organizations.save(new WorkspaceOrganization(id, name, null, java.time.Instant.now())));
    }

    private void seed(AccountStore accounts, PasswordEncoder encoder, String email, String password,
                      String name, String role, UUID organizationId) {
        accounts.findByEmail(email).orElseGet(() -> accounts.save(new UserAccount(UUID.randomUUID(), organizationId,
                email, encoder.encode(password), name, role, java.time.Instant.now())));
    }

    private static final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {
        private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
        private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(jakarta.servlet.http.HttpServletRequest request,
                           jakarta.servlet.http.HttpServletResponse response, java.util.function.Supplier<CsrfToken> token) {
            xor.handle(request, response, token);
            token.get();
        }

        @Override
        public String resolveCsrfTokenValue(jakarta.servlet.http.HttpServletRequest request, CsrfToken token) {
            String header = request.getHeader(token.getHeaderName());
            return StringUtils.hasText(header)
                    ? plain.resolveCsrfTokenValue(request, token)
                    : xor.resolveCsrfTokenValue(request, token);
        }
    }
}
