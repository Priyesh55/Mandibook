package com.mandi.security;

import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import jakarta.servlet.DispatcherType;

/**
 * The business application's security chain (P1-04). Deny by default: only the endpoints listed under "public" are
 * reachable without a session, every other {@code /api/**} request needs one, and anything else is refused. Sessions are
 * server-side (Spring Session JDBC); no token is ever handed to scripts.
 */
@Configuration
@EnableWebSecurity
@EnableScheduling
@EnableConfigurationProperties(SecurityProps.class)
public class SecurityConfig {

    /** The CSRF cookie is readable by the page (it must echo it in a header). With a secure session cookie it gets the __Host- prefix too. */
    @Bean
    CsrfTokenRepository csrfTokenRepository(@Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repo.setCookieName(secure ? "__Host-XSRF-TOKEN" : "XSRF-TOKEN");
        repo.setCookieCustomizer(c -> c.secure(secure).sameSite("Lax").path("/"));
        return repo;
    }

    /**
     * The session cookie, built EXPLICITLY from `server.servlet.session.cookie.*` (name, Secure, HttpOnly, SameSite, Path).
     * Left to defaults, Spring Session takes these from the servlet container (absent in some contexts) and decides Secure
     * from the incoming request, which is false behind a TLS-terminating proxy. No Max-Age: a browser-session cookie.
     */
    @Bean
    org.springframework.session.web.http.DefaultCookieSerializer cookieSerializer(
            @Value("${server.servlet.session.cookie.name:SESSION}") String name,
            @Value("${server.servlet.session.cookie.secure:true}") boolean secure,
            @Value("${server.servlet.session.cookie.http-only:true}") boolean httpOnly,
            @Value("${server.servlet.session.cookie.same-site:lax}") String sameSite,
            @Value("${server.servlet.session.cookie.path:/}") String path) {
        org.springframework.session.web.http.DefaultCookieSerializer s = new org.springframework.session.web.http.DefaultCookieSerializer();
        s.setCookieName(name);
        s.setUseSecureCookie(secure);
        s.setUseHttpOnlyCookie(httpOnly);
        s.setSameSite(sameSite.substring(0, 1).toUpperCase() + sameSite.substring(1).toLowerCase());
        s.setCookiePath(path);
        return s;
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /** One clock for the absolute limit; tests replace it with a controllable one. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SecurityFilterChain businessChain(HttpSecurity http, SecurityProps props, CsrfTokenRepository csrfTokenRepository,
                                      SecurityContextRepository contextRepository, Clock clock, AuthEventService events,
                                      DataSource dataSource,
                                      @Value("${mandi.mail.dev-sink:false}") boolean devMailSink) throws Exception {
        http
                .csrf(c -> c.csrfTokenRepository(csrfTokenRepository).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .securityContext(c -> c.securityContextRepository(contextRepository))
                // no saved request (it would create a session for an anonymous visitor), no form, no basic, no built-in logout
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(a -> { a
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // ---- the complete list of public endpoints ----
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").permitAll()      // idempotent; CSRF still required
                        // an invited person has no account yet: needs the emailed token, CSRF, and the sign-in throttle
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/invitations/accept").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        ; if (devMailSink) {
                            // DEVELOPMENT/TEST ONLY (the property is refused at start-up elsewhere): the captured invitation links
                            a.requestMatchers(HttpMethod.GET, "/api/v1/dev/mail").permitAll();
                        }
                        // ---- everything else ----
                        a.requestMatchers("/api/**").authenticated().anyRequest().denyAll(); })
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> JsonErrors.write(res, 401, "UNAUTHENTICATED"))
                        .accessDeniedHandler((req, res, ex) ->
                                JsonErrors.write(res, 403, ex instanceof CsrfException ? "CSRF_REJECTED" : "FORBIDDEN")))
                .addFilterBefore(new OriginFilter(props.allowedOrigins()), CsrfFilter.class)
                .addFilterAfter(new SessionPolicyFilter(clock, props, events), AnonymousAuthenticationFilter.class)
                // after authorization: only a signed-in request gets here; verifies firm selection, membership and account on EVERY
                // business request (everything under /api/ except /api/v1/auth/), so no route needs a check of its own
                .addFilterAfter(new FirmContextFilter(dataSource), AuthorizationFilter.class);
        return http.build();
    }

    /** Answers GET /api/v1/auth/session before the session filter, so asking never counts as activity (memo 10.3). */
    @Bean
    FilterRegistrationBean<PassiveSessionStatusFilter> passiveSessionStatusFilter(DataSource dataSource, SecurityProps props,
            @Value("${server.servlet.session.cookie.name:JSESSIONID}") String cookieName) {
        FilterRegistrationBean<PassiveSessionStatusFilter> r = new FilterRegistrationBean<>(new PassiveSessionStatusFilter(dataSource, props, cookieName));
        r.setOrder(Ordered.HIGHEST_PRECEDENCE);
        r.addUrlPatterns(PassiveSessionStatusFilter.PATH);
        return r;
    }
}
