package com.smartcoffee.infrastructure.observability;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long s = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            log.info("http method={} path={} status={} durationMs={}", req.getMethod(), req.getRequestURI(),
                    res.getStatus(), (System.nanoTime() - s) / 1_000_000);
        }
    }
}

