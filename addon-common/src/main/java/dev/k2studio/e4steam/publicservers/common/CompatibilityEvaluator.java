package dev.k2studio.e4steam.publicservers.common;

import dev.k2studio.e4steam.publicservers.contract.ListingMetadata;
import dev.k2studio.e4steam.publicservers.contract.PublicServerEntry;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/** Deterministic fail-closed evaluator used for display, filters, sorting and join admission. */
public final class CompatibilityEvaluator {
    public Compatibility evaluate(PublicServerEntry entry, ClientEnvironment local, Instant now) {
        if (entry == null || local == null || now == null) throw new NullPointerException("compatibility input");
        ListingMetadata remote = entry.metadata();
        if (entry.stale(now)) return incompatible(Compatibility.Reason.STALE, "");
        if (remote.directoryProtocol() != local.directoryProtocol()) {
            return incompatible(Compatibility.Reason.DIRECTORY_PROTOCOL,
                    Integer.toString(remote.directoryProtocol()));
        }
        if (remote.coreWireProtocol() != local.coreWireProtocol()) {
            return incompatible(Compatibility.Reason.CORE_PROTOCOL,
                    Integer.toString(remote.coreWireProtocol()));
        }
        if (!remote.minecraftVersion().equals(local.minecraftVersion())) {
            return incompatible(Compatibility.Reason.MINECRAFT_VERSION, remote.minecraftVersion());
        }
        if (!remote.loader().equalsIgnoreCase(local.loader())) {
            return incompatible(Compatibility.Reason.LOADER, remote.loader().toLowerCase(Locale.ROOT));
        }
        if (!sameE4steamLine(remote.e4steamVersion(), local.e4steamVersion())) {
            return incompatible(Compatibility.Reason.E4STEAM_VERSION, remote.e4steamVersion());
        }
        for (Map.Entry<String, String> requirement : remote.requiredAddons().entrySet()) {
            String installed = local.installedAddons().get(requirement.getKey());
            if (installed == null) {
                return incompatible(Compatibility.Reason.MISSING_REQUIRED_ADDON, requirement.getKey());
            }
            if (!VersionRange.matches(installed, requirement.getValue())) {
                return incompatible(Compatibility.Reason.INCOMPATIBLE_REQUIRED_ADDON, requirement.getKey());
            }
        }
        if (remote.players() >= remote.capacity()) {
            return new Compatibility(true, false, Compatibility.Reason.FULL, "");
        }
        return Compatibility.compatibleResult();
    }

    private static Compatibility incompatible(Compatibility.Reason reason, String detail) {
        return new Compatibility(false, false, reason, detail);
    }

    private static boolean sameE4steamLine(String remote, String local) {
        int[] left = VersionRange.parse(remote);
        int[] right = VersionRange.parse(local);
        if (left == null || right == null) return remote.equals(local);
        if (left[0] != right[0]) return false;
        return left[0] != 0 || left[1] == right[1];
    }

    /** Small strict range evaluator for exact, wildcard, caret, tilde and comma-separated bounds. */
    static final class VersionRange {
        private VersionRange() { }

        static boolean matches(String version, String range) {
            int[] actual = parse(version);
            if (actual == null || range == null || range.isBlank()) return false;
            String checked = range.trim();
            if (checked.equals("*")) return true;
            if (checked.startsWith("^")) {
                int[] base = parse(checked.substring(1));
                if (base == null || compare(actual, base) < 0) return false;
                int[] ceiling = base[0] > 0 ? new int[]{base[0] + 1, 0, 0}
                        : base[1] > 0 ? new int[]{0, base[1] + 1, 0}
                        : new int[]{0, 0, base[2] + 1};
                return compare(actual, ceiling) < 0;
            }
            if (checked.startsWith("~")) {
                int[] base = parse(checked.substring(1));
                return base != null && compare(actual, base) >= 0
                        && compare(actual, new int[]{base[0], base[1] + 1, 0}) < 0;
            }
            if (checked.endsWith(".*")) {
                String[] pieces = checked.substring(0, checked.length() - 2).split("\\.");
                if (pieces.length < 1 || pieces.length > 2) return false;
                try {
                    if (actual[0] != Integer.parseInt(pieces[0])) return false;
                    return pieces.length == 1 || actual[1] == Integer.parseInt(pieces[1]);
                } catch (NumberFormatException failure) { return false; }
            }
            if (checked.indexOf(',') >= 0 || checked.startsWith(">") || checked.startsWith("<")) {
                for (String clause : checked.split(",")) {
                    String part = clause.trim();
                    String operator;
                    if (part.startsWith(">=") || part.startsWith("<=")) operator = part.substring(0, 2);
                    else if (part.startsWith(">") || part.startsWith("<")) operator = part.substring(0, 1);
                    else return false;
                    int[] bound = parse(part.substring(operator.length()));
                    if (bound == null) return false;
                    int comparison = compare(actual, bound);
                    if (operator.equals(">=") && comparison < 0 || operator.equals(">") && comparison <= 0
                            || operator.equals("<=") && comparison > 0 || operator.equals("<") && comparison >= 0) {
                        return false;
                    }
                }
                return true;
            }
            int[] exact = parse(checked);
            return exact != null && compare(actual, exact) == 0;
        }

        static int[] parse(String value) {
            if (value == null) return null;
            String core = value.trim();
            int suffix = core.indexOf('-');
            if (suffix >= 0) core = core.substring(0, suffix);
            suffix = core.indexOf('+');
            if (suffix >= 0) core = core.substring(0, suffix);
            String[] pieces = core.split("\\.", -1);
            if (pieces.length < 2 || pieces.length > 3) return null;
            try {
                int[] result = new int[]{Integer.parseInt(pieces[0]), Integer.parseInt(pieces[1]),
                        pieces.length == 3 ? Integer.parseInt(pieces[2]) : 0};
                return result[0] < 0 || result[1] < 0 || result[2] < 0 ? null : result;
            } catch (NumberFormatException failure) { return null; }
        }

        private static int compare(int[] left, int[] right) {
            for (int index = 0; index < 3; index++) {
                int result = Integer.compare(left[index], right[index]);
                if (result != 0) return result;
            }
            return 0;
        }
    }
}
