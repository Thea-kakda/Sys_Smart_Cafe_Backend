package com.smartcoffee.modules.identity.api;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Self-service profile update for the authenticated user (PUT /api/v1/users/me).
 * Every field is optional: a null value means "leave unchanged". Bean Validation
 * treats null as valid for {@code @Size}/{@code @Pattern}, so a rule only fires
 * when the client actually supplies the field. There is no id parameter — the
 * target is always the authenticated actor.
 */
public record UpdateUserProfileRequest(
        @Size(max = 150) String fullName,
        @Pattern(regexp = "^\\+855\\d{8,9}$", message = "phone must match +855 followed by 8 or 9 digits") String phone,
        @Pattern(regexp = "^(en|km|en-US|km-KH)$", message = "preferredLanguage must be one of 'en', 'km', 'en-US', 'km-KH'") String preferredLanguage) {
}
