package com.smartcoffee.common.context;

public final class CorrelationContext {
    private static final ThreadLocal<RequestContext> CTX = new ThreadLocal<>();

    private CorrelationContext() {
    }

    public static void set(RequestContext c) {
        CTX.set(c);
    }

    public static RequestContext get() {
        return CTX.get();
    }

    public static String requestId() {
        var c = CTX.get();
        return c == null ? "unknown" : c.requestId();
    }

    public static void clear() {
        CTX.remove();
    }
}

