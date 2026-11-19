package com.smartcoffee.modules.auth.api;

import jakarta.validation.constraints.*;

public record RegisterRequest(@NotBlank @Size(max = 150) String fullName,
        @NotBlank @Email @Size(max = 320) String email, @NotBlank @Size(min = 10, max = 128) String password,
        @NotBlank @Pattern(regexp = "^(en|km|en-US|km-KH)$", message = "preferredLanguage must be one of 'en', 'km', 'en-US', 'km-KH'") String preferredLanguage) {
}
