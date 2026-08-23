package io.github.fragudev.ailab;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sets a Content-Security-Policy on every response (docs/threat-model.md T8). Strict enough to
 * matter: {@code script-src 'self'} and {@code style-src 'self'} carry no {@code unsafe-inline},
 * so the static UI's script and stylesheet live in their own files ({@code app.js}/{@code
 * app.css}) rather than inline blocks — an inline exception here would make the header
 * decorative against the exact injection class it exists to stop.
 */
@Component
class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final String CONTENT_SECURITY_POLICY = String.join(
            "; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self'",
            "img-src 'self' data:",
            "font-src 'self'",
            "connect-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "frame-ancestors 'none'",
            "form-action 'self'");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        response.setHeader("X-Content-Type-Options", "nosniff");
        chain.doFilter(request, response);
    }
}
