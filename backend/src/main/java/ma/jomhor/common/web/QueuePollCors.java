package ma.jomhor.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * CORS for GET /fan/queue only (docs/04-api.md §Fan, plan Q152): the shop's browser polls the API directly with
 * {@code X-Queue-Token} and no credentials. Allowed from {@code SHOP_ORIGIN} alone; the preflight is cached
 * ({@code Access-Control-Max-Age}) so a poll isn't preceded by one. Ahead of Spring Security, so the preflight (an
 * OPTIONS, which no security chain permits) is answered here. The filter acts on that GET and its preflight only: any
 * other path or method (the join's POST included) passes through untouched, with no CORS headers. The source is not a
 * bean on purpose: a CorsConfigurationSource bean would be applied to every security chain. Outside dev and test an
 * empty SHOP_ORIGIN fails startup (like TRUSTED_PROXIES_REGEX in ClientIpConfig): the shop's queue page would
 * otherwise fail silently on sale day.
 */
@Configuration(proxyBeanMethods = false)
class QueuePollCors {

    /** Chromium's cap on Access-Control-Max-Age. */
    static final Duration MAX_AGE = Duration.ofHours(2);

    @Bean
    FilterRegistrationBean<CorsFilter> queuePollCorsFilter(@Value("${jomhor.cors.shop-origin}") String shopOrigin,
                                                           Environment env) {
        if (shopOrigin.isBlank() && !env.matchesProfiles("dev | test")) {
            throw new IllegalStateException(
                    "SHOP_ORIGIN must be set: the web shop's origin, the only one allowed to poll GET /fan/queue");
        }
        CorsConfiguration config = new CorsConfiguration();
        if (!shopOrigin.isBlank()) {
            config.setAllowedOrigins(List.of(shopOrigin));
        }
        config.setAllowedMethods(List.of("GET"));
        config.setAllowedHeaders(List.of("X-Queue-Token"));
        config.setAllowCredentials(false);
        config.setMaxAge(MAX_AGE);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/fan/queue", config);
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source) {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return !"GET".equals(request.getMethod()) && !CorsUtils.isPreFlightRequest(request);
            }
        });
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
