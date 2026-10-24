package dev.k2studio.e4steam.publicservers.contract;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/** Shared strict validation for every remotely displayed string. */
public final class TextPolicy {
    private static final Set<String> TAGS = Set.of(
            "survival", "creative", "adventure", "vanilla", "modded",
            "pve", "pvp", "minigames", "roleplay", "ru", "eu", "na", "asia"
    );

    private TextPolicy() {
    }

    public static String title(String value) {
        return displayText(value, "name", 48, false);
    }

    public static String description(String value) {
        return displayText(value, "description", 160, true);
    }

    public static String displayText(String value, String field, int maximum, boolean emptyAllowed) {
        if (value == null) throw new NullPointerException(field);
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFC);
        if ((!emptyAllowed && normalized.isEmpty()) || normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " has an invalid length");
        }
        boolean latin = false;
        boolean cyrillic = false;
        boolean greek = false;
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || codePoint == 0x2028 || codePoint == 0x2029) {
                throw new IllegalArgumentException(field + " contains control or formatting characters");
            }
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            latin |= script == Character.UnicodeScript.LATIN;
            cyrillic |= script == Character.UnicodeScript.CYRILLIC;
            greek |= script == Character.UnicodeScript.GREEK;
        }
        if (!field.equals("name") && (latin ? 1 : 0) + (cyrillic ? 1 : 0) + (greek ? 1 : 0) > 1) {
            throw new IllegalArgumentException(field + " mixes confusable scripts");
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("<") || lower.contains(">") || lower.contains("http://")
                || lower.contains("https://") || lower.contains("www.")
                || lower.contains("[") || lower.contains("](")
                || lower.contains("**") || lower.contains("__") || lower.contains("`")
                || lower.contains("javascript:")) {
            throw new IllegalArgumentException(field + " contains markup or a link");
        }
        return normalized;
    }

    public static String tag(String value) {
        if (value == null) throw new NullPointerException("tag");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!TAGS.contains(normalized)) throw new IllegalArgumentException("tag is not allowlisted");
        return normalized;
    }

    public static String identifier(String value, String field, int maximum) {
        if (value == null) throw new NullPointerException(field);
        String checked = value.trim();
        if (checked.isEmpty() || checked.length() > maximum
                || !checked.matches("[A-Za-z0-9][A-Za-z0-9._:+/-]*")) {
            throw new IllegalArgumentException(field + " has an invalid format");
        }
        return checked;
    }

    public static String opaque(String value, String field, int maximum) {
        String checked = identifier(value, field, maximum);
        String lower = checked.toLowerCase(Locale.ROOT);
        if (lower.contains("token") || lower.contains("ticket") || lower.contains("secret")
                || lower.contains("password") || lower.contains("cookie")
                || lower.matches(".*(?:^|[/:])(?:localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0)(?:[/:]|$).*")
                || lower.matches(".*(?:[0-9]{1,3}\\.){3}[0-9]{1,3}:[0-9]{1,5}.*")) {
            throw new IllegalArgumentException(field + " contains forbidden routing or credential data");
        }
        return checked;
    }
}
