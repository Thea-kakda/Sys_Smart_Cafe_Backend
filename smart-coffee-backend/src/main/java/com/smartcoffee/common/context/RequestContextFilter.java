package com.smartcoffee.common.context;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component("smartCoffeeRequestContextFilter")
public class RequestContextFilter extends OncePerRequestFilter {
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String rid = safe(req.getHeader("X-Request-Id"));
        String cid = safe(req.getHeader("X-Correlation-Id"));
        Locale locale = req.getLocale();
        var ctx = new RequestContext(rid, cid, locale, req.getRemoteAddr(), req.getHeader("User-Agent"));
        CorrelationContext.set(ctx);
        MDC.put("requestId", rid);
        MDC.put("correlationId", cid);
        res.setHeader("X-Request-Id", rid);
        res.setHeader("X-Correlation-Id", cid);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.clear();
            CorrelationContext.clear();
        }
    }

    private String safe(String value) {
        if (value != null && value.matches("[A-Za-z0-9._:-]{1,100}"))
            return value;
        return UUID.randomUUID().toString();
    }
}

