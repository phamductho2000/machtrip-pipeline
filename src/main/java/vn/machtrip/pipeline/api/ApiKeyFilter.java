package vn.machtrip.pipeline.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import vn.machtrip.pipeline.config.PipelineProperties;

/**
 * Guards every {@code /api/**} request: the caller must send header {@code X-API-Key} equal to
 * {@code pipeline.api.key} (env {@code API_KEY}). Same fail-closed, constant-time pattern as the Apify webhook
 * secret check in {@code WebhookController}: a blank configured key rejects everything rather than opening the API.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final byte[] key;

    public ApiKeyFilter(PipelineProperties props) {
        this.key = props.api().key().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        byte[] givenBytes = (given == null ? "" : given).getBytes(StandardCharsets.UTF_8);
        if (key.length == 0 || !MessageDigest.isEqual(key, givenBytes)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"missing or invalid X-API-Key\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
