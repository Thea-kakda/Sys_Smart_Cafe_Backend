package com.smartcoffee.modules.auth.api;

import java.util.UUID;

public record AuthResponse(UUID userId, String fullName, String email, String preferredLanguage, String accessToken) {
}
