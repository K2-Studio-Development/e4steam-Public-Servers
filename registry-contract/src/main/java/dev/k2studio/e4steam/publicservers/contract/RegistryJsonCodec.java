package dev.k2studio.e4steam.publicservers.contract;

import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.AttestationReceipt;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.ChallengeRequest;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.ChallengeResponse;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.CreatePublicationRequest;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.ErrorResponse;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.JoinHandleRequest;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.JoinHandleResponse;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.LeaseMutationRequest;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.PublicationLease;
import dev.k2studio.e4steam.publicservers.contract.RegistryMessages.ReportRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit schema mapper. Unknown fields and unknown message types fail closed. */
public final class RegistryJsonCodec {
    private RegistryJsonCodec() {
    }

    public static byte[] encode(Object message) {
        if (message == null) throw new NullPointerException("message");
        return StrictJson.write(toTree(message), limit(message.getClass()));
    }

    public static <T> T decode(byte[] input, Class<T> type) {
        if (type == null) throw new NullPointerException("type");
        Object tree = StrictJson.parse(input, limit(type));
        Object decoded = fromTree(StrictJson.object(tree, "root"), type);
        return type.cast(decoded);
    }

    private static int limit(Class<?> type) {
        return type == ChallengeResponse.class
                || type == AttestationReceipt.class
                || type == PublicationLease.class
                || type == JoinHandleResponse.class
                || type == ErrorResponse.class
                || type == DirectoryPage.class
                || type == PublicServerEntry.class
                ? DirectoryProtocol.MAX_RESPONSE_BYTES
                : DirectoryProtocol.MAX_REQUEST_BYTES;
    }

    private static Object fromTree(Map<String, Object> object, Class<?> type) {
        if (type == ChallengeRequest.class) {
            exact(object, "type", "action");
            return new ChallengeRequest(enumValue(PublicServerType.class, text(object, "type")), text(object, "action"));
        }
        if (type == ChallengeResponse.class) {
            exact(object, "challengeId", "nonce", "audience", "expiresAt");
            return new ChallengeResponse(text(object, "challengeId"), text(object, "nonce"),
                    text(object, "audience"), stamp(object, "expiresAt"));
        }
        if (type == AttestationReceipt.class) {
            exact(object, "receipt", "ownerRef", "expiresAt");
            return new AttestationReceipt(text(object, "receipt"), text(object, "ownerRef"), stamp(object, "expiresAt"));
        }
        if (type == CreatePublicationRequest.class) {
            exact(object, "type", "metadata", "targetRef", "attestationReceipt");
            return new CreatePublicationRequest(
                    enumValue(PublicServerType.class, text(object, "type")),
                    metadata(StrictJson.object(required(object, "metadata"), "metadata")),
                    text(object, "targetRef"), text(object, "attestationReceipt"));
        }
        if (type == PublicationLease.class) {
            exact(object, "entry", "leaseId", "leaseProof", "heartbeatAfterSeconds");
            return new PublicationLease(entry(StrictJson.object(required(object, "entry"), "entry")),
                    text(object, "leaseId"), text(object, "leaseProof"), number(object, "heartbeatAfterSeconds"));
        }
        if (type == LeaseMutationRequest.class) {
            if (!object.keySet().equals(java.util.Set.of("leaseId", "leaseProof", "expectedRevision"))
                    && !object.keySet().equals(java.util.Set.of("leaseId", "leaseProof", "expectedRevision", "metadata"))) {
                throw new IllegalArgumentException("unknown lease mutation field");
            }
            Object metadata = object.get("metadata");
            return new LeaseMutationRequest(text(object, "leaseId"), text(object, "leaseProof"),
                    number(object, "expectedRevision"), metadata == null ? null
                    : metadata(StrictJson.object(metadata, "metadata")));
        }
        if (type == JoinHandleRequest.class) {
            exact(object, "listingId");
            return new JoinHandleRequest(text(object, "listingId"));
        }
        if (type == JoinHandleResponse.class) {
            exact(object, "handleId", "targetRef", "expiresAt");
            return new JoinHandleResponse(text(object, "handleId"), text(object, "targetRef"), stamp(object, "expiresAt"));
        }
        if (type == ReportRequest.class) {
            exact(object, "listingId", "reason");
            return new ReportRequest(text(object, "listingId"), enumValue(ReportReason.class, text(object, "reason")));
        }
        if (type == ErrorResponse.class) {
            exact(object, "code", "messageKey", "retryable");
            return new ErrorResponse(text(object, "code"), text(object, "messageKey"), StrictJson.bool(object, "retryable"));
        }
        if (type == DirectoryPage.class) {
            exact(object, "entries", "nextCursor", "generatedAt");
            List<PublicServerEntry> entries = new ArrayList<>();
            for (Object value : StrictJson.array(required(object, "entries"), "entries")) {
                entries.add(entry(StrictJson.object(value, "entry")));
            }
            return new DirectoryPage(entries, StrictJson.optionalString(object, "nextCursor"), stamp(object, "generatedAt"));
        }
        throw new IllegalArgumentException("unsupported registry message type: " + type.getName());
    }

    private static Object toTree(Object message) {
        LinkedHashMap<String, Object> object = new LinkedHashMap<>();
        if (message instanceof ChallengeRequest value) {
            object.put("type", value.type().name()); object.put("action", value.action()); return object;
        }
        if (message instanceof ChallengeResponse value) {
            object.put("challengeId", value.challengeId()); object.put("nonce", value.nonce());
            object.put("audience", value.audience()); object.put("expiresAt", value.expiresAt().epochMillis()); return object;
        }
        if (message instanceof AttestationReceipt value) {
            object.put("receipt", value.receipt()); object.put("ownerRef", value.ownerRef());
            object.put("expiresAt", value.expiresAt().epochMillis()); return object;
        }
        if (message instanceof CreatePublicationRequest value) {
            object.put("type", value.type().name()); object.put("metadata", metadataTree(value.metadata()));
            object.put("targetRef", value.targetRef()); object.put("attestationReceipt", value.attestationReceipt()); return object;
        }
        if (message instanceof PublicationLease value) {
            object.put("entry", entryTree(value.entry())); object.put("leaseId", value.leaseId());
            object.put("leaseProof", value.leaseProof()); object.put("heartbeatAfterSeconds", value.heartbeatAfterSeconds()); return object;
        }
        if (message instanceof LeaseMutationRequest value) {
            object.put("leaseId", value.leaseId()); object.put("leaseProof", value.leaseProof());
            object.put("expectedRevision", value.expectedRevision());
            if (value.metadata() != null) object.put("metadata", metadataTree(value.metadata())); return object;
        }
        if (message instanceof JoinHandleRequest value) { object.put("listingId", value.listingId()); return object; }
        if (message instanceof JoinHandleResponse value) {
            object.put("handleId", value.handleId()); object.put("targetRef", value.targetRef());
            object.put("expiresAt", value.expiresAt().epochMillis()); return object;
        }
        if (message instanceof ReportRequest value) {
            object.put("listingId", value.listingId()); object.put("reason", value.reason().name()); return object;
        }
        if (message instanceof ErrorResponse value) {
            object.put("code", value.code()); object.put("messageKey", value.messageKey());
            object.put("retryable", value.retryable()); return object;
        }
        if (message instanceof DirectoryPage value) {
            List<Object> entries = new ArrayList<>(); value.entries().forEach(entry -> entries.add(entryTree(entry)));
            object.put("entries", entries); object.put("nextCursor", value.nextCursor());
            object.put("generatedAt", value.generatedAt().epochMillis()); return object;
        }
        if (message instanceof PublicServerEntry value) return entryTree(value);
        if (message instanceof ListingMetadata value) return metadataTree(value);
        throw new IllegalArgumentException("unsupported registry message: " + message.getClass().getName());
    }

    private static Map<String, Object> metadataTree(ListingMetadata value) {
        LinkedHashMap<String, Object> object = new LinkedHashMap<>();
        object.put("schemaVersion", value.schemaVersion()); object.put("name", value.name());
        object.put("description", value.description()); object.put("tags", value.tags());
        object.put("minecraftVersion", value.minecraftVersion()); object.put("loader", value.loader());
        object.put("e4steamVersion", value.e4steamVersion()); object.put("coreWireProtocol", value.coreWireProtocol());
        object.put("addonVersion", value.addonVersion()); object.put("directoryProtocol", value.directoryProtocol());
        object.put("players", value.players()); object.put("capacity", value.capacity());
        object.put("accessMode", value.accessMode()); object.put("requiredAddons", value.requiredAddons());
        return object;
    }

    private static ListingMetadata metadata(Map<String, Object> object) {
        exact(object, "schemaVersion", "name", "description", "tags", "minecraftVersion", "loader",
                "e4steamVersion", "coreWireProtocol", "addonVersion", "directoryProtocol", "players",
                "capacity", "accessMode", "requiredAddons");
        List<String> tags = new ArrayList<>();
        for (Object value : StrictJson.array(required(object, "tags"), "tags")) {
            if (!(value instanceof String text)) throw new IllegalArgumentException("tag must be a string");
            tags.add(text);
        }
        Map<String, Object> addonTree = StrictJson.object(required(object, "requiredAddons"), "requiredAddons");
        LinkedHashMap<String, String> addons = new LinkedHashMap<>();
        addonTree.forEach((key, value) -> {
            if (!(value instanceof String text)) throw new IllegalArgumentException("addon range must be a string");
            addons.put(key, text);
        });
        return new ListingMetadata(Math.toIntExact(number(object, "schemaVersion")), text(object, "name"),
                StrictJson.optionalString(object, "description"), tags, text(object, "minecraftVersion"),
                text(object, "loader"), text(object, "e4steamVersion"),
                Math.toIntExact(number(object, "coreWireProtocol")), text(object, "addonVersion"),
                Math.toIntExact(number(object, "directoryProtocol")), Math.toIntExact(number(object, "players")),
                Math.toIntExact(number(object, "capacity")), text(object, "accessMode"), addons);
    }

    private static Map<String, Object> entryTree(PublicServerEntry value) {
        LinkedHashMap<String, Object> object = new LinkedHashMap<>();
        object.put("listingId", value.listingId()); object.put("revision", value.revision());
        object.put("type", value.type().name()); object.put("metadata", metadataTree(value.metadata()));
        object.put("lastHeartbeat", value.lastHeartbeat().toEpochMilli());
        object.put("expiresAt", value.expiresAt().toEpochMilli()); object.put("ownerRef", value.ownerRef());
        return object;
    }

    private static PublicServerEntry entry(Map<String, Object> object) {
        exact(object, "listingId", "revision", "type", "metadata", "lastHeartbeat", "expiresAt", "ownerRef");
        return new PublicServerEntry(text(object, "listingId"), number(object, "revision"),
                enumValue(PublicServerType.class, text(object, "type")),
                metadata(StrictJson.object(required(object, "metadata"), "metadata")),
                Instant.ofEpochMilli(number(object, "lastHeartbeat")),
                Instant.ofEpochMilli(number(object, "expiresAt")), text(object, "ownerRef"));
    }

    private static InstantStamp stamp(Map<String, Object> object, String field) {
        return new InstantStamp(number(object, field));
    }
    private static String text(Map<String, Object> object, String field) { return StrictJson.string(object, field); }
    private static long number(Map<String, Object> object, String field) { return StrictJson.integer(object, field); }
    private static Object required(Map<String, Object> object, String field) {
        if (!object.containsKey(field) || object.get(field) == null) throw new IllegalArgumentException("missing " + field);
        return object.get(field);
    }
    private static void exact(Map<String, Object> object, String... fields) {
        java.util.Set<String> expected = java.util.Set.of(fields);
        if (!object.keySet().equals(expected)) throw new IllegalArgumentException("unknown or missing JSON field");
    }
    private static <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException failure) { throw new IllegalArgumentException("unknown " + type.getSimpleName(), failure); }
    }
}
