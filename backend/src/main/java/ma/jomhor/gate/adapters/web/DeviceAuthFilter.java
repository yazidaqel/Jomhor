package ma.jomhor.gate.adapters.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import ma.jomhor.common.web.ApiError;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.gate.application.DeviceService;
import ma.jomhor.gate.application.DeviceService.SignedRequest;
import ma.jomhor.gate.application.GateDevice;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every {@code /gate/**} request (docs/04-api.md §Gate auth), scoped by the {@code /gate/*} servlet mapping alone (it
 * matches the decoded path, like Security and MVC: no second, raw-path check that an encoded path could slip past). Runs after Spring Security, whose chain lets /gate/**
 * through: this filter is the gate's only authentication. It reads the raw body once, verifies the device signature
 * over the raw path + query and that body, and hands the same bytes on to the controller. Failures answer here in the
 * 04 error shape; success sets {@link GateDevice#ATTRIBUTE} (or, on enrolment, {@link #ENROL_KEY}).
 * Registered through {@link Registration} rather than as a component, so web slice tests don't pull the gate in.
 */
class DeviceAuthFilter extends OncePerRequestFilter {

    @Configuration(proxyBeanMethods = false)
    static class Registration {
        @Bean
        FilterRegistrationBean<DeviceAuthFilter> deviceAuthFilter(DeviceService devices, JsonMapper json) {
            var bean = new FilterRegistrationBean<>(new DeviceAuthFilter(devices, json));
            bean.addUrlPatterns("/gate/*");
            return bean;
        }
    }

    /** The SPKI DER the enrolment request was verified with: the controller enrols this key, not a re-parse. */
    static final String ENROL_KEY = "ma.jomhor.gate.enrolKey";
    static final String ENROL_PATH = "/gate/devices/enrol";
    /** A 500-row scan batch is about 150 KB. */
    static final int MAX_BODY = 1024 * 1024;

    private final DeviceService devices;
    private final JsonMapper json;

    DeviceAuthFilter(DeviceService devices, JsonMapper json) {
        this.devices = devices;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            write(response, new ApiException("BAD_REQUEST", HttpStatus.CONTENT_TOO_LARGE, "Content Too Large"));
            return;
        }
        String query = request.getQueryString();
        var signed = new SignedRequest(request.getHeader("X-Device-Id"), request.getHeader("X-Device-Time"),
                request.getHeader("X-Device-Signature"), request.getMethod(),
                request.getRequestURI() + (query == null ? "" : "?" + query), body);
        try {
            if (request.getMethod().equals("POST") && decodedPath(request).equals(ENROL_PATH)) {
                byte[] key = enrolKey(body);
                devices.authenticateEnrolment(key, signed);
                request.setAttribute(ENROL_KEY, key);
            } else {
                request.setAttribute(GateDevice.ATTRIBUTE, devices.authenticate(signed));
            }
        } catch (ApiException e) {
            write(response, e);
            return;
        }
        chain.doFilter(new CachedBody(request, body), response);
    }

    /** {@code public_key} of the body (base64url SPKI DER), or null if the body has none. */
    private byte[] enrolKey(byte[] body) {
        try {
            JsonNode key = json.readTree(body).path("public_key");
            return key.isString() ? Base64.getUrlDecoder().decode(key.asString()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The decoded path the servlet mapping and Spring MVC route on (servlet path + path info), never the raw URI:
     * {@code /v1/%67ate/devices/enrol} is the enrol route too. The signed PATH stays raw.
     */
    private static String decodedPath(HttpServletRequest request) {
        String info = request.getPathInfo();
        return request.getServletPath() + (info == null ? "" : info);
    }

    private void write(HttpServletResponse response, ApiException e) throws IOException {
        response.setStatus(e.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), new ApiError(e.code(), e.getMessage(), e.details()));
    }

    /** Replays the bytes already read, so the controller binds exactly what was signed. */
    private static final class CachedBody extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBody(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            var in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(),
                    encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding)));
        }
    }
}
