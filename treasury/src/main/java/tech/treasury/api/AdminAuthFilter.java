package tech.treasury.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Guards {@code /api/admin/**} with a shared token (env {@code ADMIN_TOKEN}). When the token is unset
 * (blank), admin is OPEN — convenient for local dev/tests, but a loud warning is logged at startup.
 * Clients send the token as {@code X-Admin-Token} or {@code Authorization: Bearer <token>}.
 *
 * <p>Deliberately a plain servlet filter (not Spring Security) — minimal surface for a demo.
 */
@Component
public class AdminAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthFilter.class);

    private final String token;

    public AdminAuthFilter(@Value("${admin.token:}") String token) {
        this.token = token == null ? "" : token.trim();
        if (this.token.isBlank()) {
            log.warn("ADMIN_TOKEN is not set — /api/admin/** is UNPROTECTED. Set ADMIN_TOKEN before any public deploy.");
        } else {
            log.info("admin endpoints protected by ADMIN_TOKEN");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (req.getRequestURI().startsWith("/api/admin") && !token.isBlank() && !token.equals(provided(req))) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"admin auth required (X-Admin-Token)\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    private static String provided(HttpServletRequest req) {
        String header = req.getHeader("X-Admin-Token");
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7).trim();
        }
        return "";
    }
}
