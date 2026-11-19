package com.smartcoffee.common.i18n;

import java.util.Locale;

/**
 * Single source of truth for locale handling (Master Prompt §2.1 / Phase 1 §5.2).
 *
 * <p>The public API accepts short aliases and full BCP-47 tags alike — {@code en},
 * {@code km}, {@code en-US}, {@code km-KH} — but the canonical values that are ever
 * persisted are only {@code en-US} and {@code km-KH}. This prevents a second
 * language representation from leaking into the same layer: callers may keep using
 * {@code en}/{@code km} translation keys in their UI, but every value crossing the
 * service boundary is normalized here before it reaches an entity.
 *
 * <p>Request DTOs already carry a {@code @Pattern} that accepts exactly these four
 * forms; {@link #normalize} is the defensive backstop for internal callers that
 * bypass bean validation. Unsupported input throws so it maps to a 400 at the edge.
 */
public final class LocaleNormalizer {

    public static final String EN_US = "en-US";
    public static final String KM_KH = "km-KH";

    private LocaleNormalizer() {
    }

    /** True for {@code en}, {@code km}, {@code en-US}, {@code km-KH} (case-insensitive tag). */
    public static boolean isSupported(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.equals("en") || v.equals("km") || v.equals("en-us") || v.equals("km-kh");
    }

    /**
     * Maps an accepted API locale to its canonical persisted form
     * ({@code en}→{@code en-US}, {@code km}→{@code km-KH}). Null passes through as
     * null so optional-update callers can distinguish "unchanged" from "set".
     *
     * @throws IllegalArgumentException if the value is not a supported locale
     */
    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "en", "en-us" -> EN_US;
            case "km", "km-kh" -> KM_KH;
            default -> throw new IllegalArgumentException("Unsupported locale: " + value);
        };
    }
}
