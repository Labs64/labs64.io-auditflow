package io.labs64.audit.broker.sqs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

import io.labs64.audit.broker.Destination;

/**
 * Queue names of the SQS adapter. SQS allows {@code [A-Za-z0-9_-]}, at most 80 characters; a tenant id
 * may also contain {@code .} and be up to 64 characters, so a tenant DLQ name that would not fit is
 * the sanitized, shortened id plus the first 8 hex digits of the SHA-256 of the id: distinct tenants
 * never share a queue. Infrastructure code that alarms per tenant DLQ can derive the same names.
 */
public final class SqsQueueNames {

    static final int MAX_LENGTH = 80;
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern INVALID_CHAR = Pattern.compile("[^A-Za-z0-9_-]");

    private final String prefix;

    public SqsQueueNames(String prefix) {
        if (prefix == null || !(prefix.isEmpty() || VALID.matcher(prefix).matches()) || prefix.length() > 40) {
            throw new IllegalArgumentException("auditflow.broker.sqs.queue-prefix must be at most 40 characters of "
                    + "[A-Za-z0-9_-], was '" + prefix + "'");
        }
        this.prefix = prefix;
    }

    public String name(Destination destination) {
        return switch (destination.kind()) {
            case INGEST -> prefix + "ingest";
            case INGEST_DLQ -> prefix + "ingest-dlq";
            // Delays are per-message delivery delays on the delivery queue itself.
            case DELIVERY, DELAY -> prefix + "delivery";
            case TENANT_DLQ -> tenantDlq(destination.tenantId());
            case QUARANTINE -> prefix + "quarantine";
        };
    }

    String tenantDlq(String tenantId) {
        String plain = prefix + "dlq-" + tenantId;
        if (VALID.matcher(tenantId).matches() && plain.length() <= MAX_LENGTH) {
            return plain;
        }
        String suffix = "-" + sha256Hex(tenantId).substring(0, 8);
        String sanitized = INVALID_CHAR.matcher(tenantId).replaceAll("_");
        int room = MAX_LENGTH - prefix.length() - "dlq-".length() - suffix.length();
        return prefix + "dlq-" + sanitized.substring(0, Math.min(room, sanitized.length())) + suffix;
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
