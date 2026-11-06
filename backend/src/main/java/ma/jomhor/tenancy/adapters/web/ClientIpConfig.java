package ma.jomhor.tenancy.adapters.web;

import org.springframework.boot.web.server.WebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * TRUSTED_PROXIES_REGEX feeds server.tomcat.remoteip.internal-proxies (application.yml). Outside dev and test it
 * must be set: a forgotten variable fails startup here, before the web server exists, instead of silently trusting
 * no proxy (every fan behind the ALB in one bucket). An empty value is allowed and means trust no proxy.
 */
@Configuration(proxyBeanMethods = false)
class ClientIpConfig {

    static final String VARIABLE = "TRUSTED_PROXIES_REGEX";

    @Bean
    WebServerFactoryCustomizer<WebServerFactory> trustedProxiesRequired(Environment env) {
        if (!env.matchesProfiles("dev | test") && env.getProperty(VARIABLE) == null) {
            throw new IllegalStateException(VARIABLE
                    + " must be set: a regex of the proxy addresses whose X-Forwarded-For is trusted (empty = none)");
        }
        return factory -> { };
    }
}
