package dev.k2studio.e4steam.publicservers.contract;

/** Fixed moderation reasons; arbitrary user text is deliberately not accepted. */
public enum ReportReason {
    MISLEADING,
    ABUSIVE_NAME,
    MALICIOUS_CONTENT,
    IMPERSONATION,
    OTHER_POLICY_VIOLATION
}
