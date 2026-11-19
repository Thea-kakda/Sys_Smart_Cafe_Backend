package com.smartcoffee.common.context;

import java.util.Locale;

public record RequestContext(String requestId, String correlationId, Locale locale, String clientIp, String userAgent) {
}

