package com.example.online.exam;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Configuration
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain applicationSecurityFilterChain(HttpSecurity http)
            throws Exception {
        HttpSessionCsrfTokenRepository csrfTokenRepository =
                new HttpSessionCsrfTokenRepository();

        return http
                .authorizeHttpRequests(authorize -> authorize
                        .anyRequest().permitAll())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(
                                new XorCsrfTokenRequestAttributeHandler()))
                .addFilterBefore(
                        new SameOriginRequestFilter(),
                        CsrfFilter.class)
                .build();
    }

    private static final class SameOriginRequestFilter
            extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain)
                throws ServletException, IOException {
            if (isUnsafeMethod(request.getMethod())
                    && !hasSameOrigin(request)) {
                response.sendError(
                        HttpServletResponse.SC_FORBIDDEN,
                        "Cross-origin state-changing request rejected.");
                return;
            }
            filterChain.doFilter(request, response);
        }

        private boolean isUnsafeMethod(String method) {
            return !List.of("GET", "HEAD", "OPTIONS", "TRACE")
                    .contains(method.toUpperCase(Locale.ROOT));
        }

        private boolean hasSameOrigin(HttpServletRequest request) {
            List<String> origins = new ArrayList<>();
            String origin = request.getHeader("Origin");
            String referer = request.getHeader("Referer");
            if (origin != null && !origin.isBlank()) {
                origins.add(origin);
            }
            if (referer != null && !referer.isBlank()) {
                origins.add(referer);
            }

            for (String value : origins) {
                URI uri;
                try {
                    uri = URI.create(value);
                } catch (IllegalArgumentException exception) {
                    return false;
                }
                if (!sameOrigin(uri, request)) {
                    return false;
                }
            }
            return true;
        }

        private boolean sameOrigin(
                URI source,
                HttpServletRequest request) {
            String scheme = source.getScheme();
            String host = source.getHost();
            if (scheme == null || host == null
                    || source.getUserInfo() != null
                    || !scheme.equalsIgnoreCase(request.getScheme())
                    || !host.equalsIgnoreCase(request.getServerName())) {
                return false;
            }

            int sourcePort = source.getPort() < 0
                    ? defaultPort(scheme)
                    : source.getPort();
            int requestPort = request.getServerPort() < 0
                    ? defaultPort(request.getScheme())
                    : request.getServerPort();
            return sourcePort == requestPort;
        }

        private int defaultPort(String scheme) {
            return "https".equalsIgnoreCase(scheme) ? 443 : 80;
        }
    }
}
