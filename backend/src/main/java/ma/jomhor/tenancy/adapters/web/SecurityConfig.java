package ma.jomhor.tenancy.adapters.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import ma.jomhor.common.Secrets;
import ma.jomhor.common.web.ApiError;
import ma.jomhor.tenancy.application.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Four stateless chains. /fan/** needs a JWT with aud=fan, /org/** one with aud=org (same HS256 key,
 * the audience is what separates them); /fan/auth/** and /org/auth/** are open. Everything else is
 * closed except /payments/** (gateway-facing, verified per provider). 401/403 bodies use the 04 shape.
 * GET /fan/events and GET /fan/events/{id} are public (04 §Fan) and sit on their own chain without a resource
 * server, so an Authorization header sent there is ignored rather than checked, and without the default
 * no-store cache headers (04: public reads carry no cache headers; the authenticated chains keep them).
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    private final SecretKey key;
    private final JsonMapper json;

    SecurityConfig(@Value("${jomhor.jwt-secret}") String secret, JsonMapper json) {
        this.key = new SecretKeySpec(Secrets.atLeast32Bytes("JWT_SECRET", secret), "HmacSHA256");
        this.json = json;
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
    }

    /** Exactly the two public reads; anything else under /fan (other methods, deeper paths) falls to fanChain. */
    @Bean
    @Order(0)
    SecurityFilterChain publicFanChain(HttpSecurity http) {
        return stateless(http)
                .securityMatchers(m -> m.requestMatchers(HttpMethod.GET, "/fan/events", "/fan/events/{id}"))
                .headers(h -> h.cacheControl(c -> c.disable()))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(1)
    SecurityFilterChain fanChain(HttpSecurity http) {
        return audienceChain(http, "/fan/**", "/fan/auth/**", AuthService.FAN);
    }

    @Bean
    @Order(2)
    SecurityFilterChain orgChain(HttpSecurity http) {
        return audienceChain(http, "/org/**", "/org/auth/**", AuthService.ORG);
    }

    @Bean
    @Order(3)
    SecurityFilterChain otherChain(HttpSecurity http) {
        return stateless(http)
                .authorizeHttpRequests(a -> a.requestMatchers("/payments/**", "/error").permitAll().anyRequest().denyAll())
                .build();
    }

    /** Firewall rejections (e.g. "..", ";", encoded "/") get the 04 error shape instead of a container page. */
    @Bean
    RequestRejectedHandler requestRejectedHandler() {
        return (request, response, e) -> write(response, HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Bad Request");
    }

    @Bean
    WebMvcConfigurer orgContext(AuthService auth) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new OrgContextInterceptor(auth))
                        .addPathPatterns("/org/**")
                        .excludePathPatterns("/org/auth/**", "/org/me");
            }
        };
    }

    private SecurityFilterChain audienceChain(HttpSecurity http, String prefix, String open, String audience) {
        return stateless(http)
                .securityMatcher(prefix)
                .authorizeHttpRequests(a -> a.requestMatchers(open).permitAll().anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .authenticationEntryPoint(unauthorized())
                        .accessDeniedHandler(forbidden())
                        .jwt(j -> j.decoder(decoder(audience))))
                .build();
    }

    private HttpSecurity stateless(HttpSecurity http) {
        return http
                .csrf(c -> c.disable())
                .logout(l -> l.disable())
                .requestCache(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized()).accessDeniedHandler(forbidden()));
    }

    private JwtDecoder decoder(String audience) {
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience))));
        return decoder;
    }

    private AuthenticationEntryPoint unauthorized() {
        return (request, response, e) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            write(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing, invalid or wrong-audience token");
        };
    }

    private AccessDeniedHandler forbidden() {
        return (request, response, e) -> write(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Forbidden");
    }

    private void write(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), new ApiError(code, message, Map.of()));
    }
}
