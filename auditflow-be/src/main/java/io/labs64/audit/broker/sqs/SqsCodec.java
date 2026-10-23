package io.labs64.audit.broker.sqs;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.labs64.audit.broker.BrokerMessage;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

/**
 * Maps a {@link BrokerMessage} to an SQS message and back. SQS allows at most 10 message attributes,
 * so all headers travel as one JSON attribute ({@link #HEADERS}); the application message id is a
 * second one. A body SQS cannot carry as text (it allows only XML characters) is sent base64-encoded.
 */
final class SqsCodec {

    static final String HEADERS = "x-auditflow-headers";
    static final String MESSAGE_ID = "x-auditflow-message-id";
    /** Adapter-internal header: epoch millis before which a delivery must not be worked on (delays > 15 min). */
    static final String NOT_BEFORE = "x-auditflow-not-before";
    /** Adapter-internal header: {@code base64} when the body is base64 text. */
    static final String BODY_ENCODING = "x-auditflow-body-encoding";

    private static final String STRING = "String";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };

    /** An SQS message ready to send. */
    record Encoded(String body, Map<String, MessageAttributeValue> attributes, int size) {
    }

    /** A received message, with what the adapter needs to settle it. */
    record Received(BrokerMessage message, String receiptHandle, String sqsMessageId, int receiveCount, long notBefore) {
    }

    private SqsCodec() {
    }

    static Encoded encode(BrokerMessage message, long notBefore) {
        Map<String, Object> headers = new LinkedHashMap<>(message.headers());
        String body = sqsText(message.body());
        if (body == null) {
            body = Base64.getEncoder().encodeToString(message.body());
            headers.put(BODY_ENCODING, "base64");
        }
        if (notBefore > 0) {
            headers.put(NOT_BEFORE, notBefore);
        }
        Map<String, MessageAttributeValue> attributes = new LinkedHashMap<>();
        if (!headers.isEmpty()) {
            attributes.put(HEADERS, text(toJson(headers)));
        }
        if (message.messageId() != null) {
            attributes.put(MESSAGE_ID, text(message.messageId()));
        }
        int size = utf8Length(body);
        for (Map.Entry<String, MessageAttributeValue> a : attributes.entrySet()) {
            size += utf8Length(a.getKey()) + STRING.length() + utf8Length(a.getValue().stringValue());
        }
        return new Encoded(body, attributes, size);
    }

    static Received decode(Message message) {
        Map<String, MessageAttributeValue> attributes = message.messageAttributes();
        Map<String, Object> headers = new LinkedHashMap<>();
        MessageAttributeValue h = attributes.get(HEADERS);
        if (h != null && h.stringValue() != null) {
            try {
                headers.putAll(JSON.readValue(h.stringValue(), MAP));
            } catch (Exception e) {
                // Not ours or corrupt: the body still reaches the application, which dead-letters it.
            }
        }
        byte[] body = "base64".equals(headers.remove(BODY_ENCODING))
                ? Base64.getDecoder().decode(message.body())
                : message.body().getBytes(StandardCharsets.UTF_8);
        Object nb = headers.remove(NOT_BEFORE);
        long notBefore = nb instanceof Number n ? n.longValue() : 0;
        MessageAttributeValue id = attributes.get(MESSAGE_ID);
        String receiveCount = message.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
        int count;
        try {
            count = receiveCount == null ? 1 : Integer.parseInt(receiveCount);
        } catch (NumberFormatException e) {
            count = 1;
        }
        return new Received(new BrokerMessage(body, headers, id == null ? null : id.stringValue()),
                message.receiptHandle(), message.messageId(), count, notBefore);
    }

    /** The body as text when it is valid UTF-8 made only of characters SQS accepts, else {@code null}. */
    static String sqsText(byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            boolean allowed = cp == 0x9 || cp == 0xA || cp == 0xD || (cp >= 0x20 && cp <= 0xD7FF)
                    || (cp >= 0xE000 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= 0x10FFFF);
            if (!allowed) {
                return null;
            }
            i += Character.charCount(cp);
        }
        return text;
    }

    private static MessageAttributeValue text(String value) {
        return MessageAttributeValue.builder().dataType(STRING).stringValue(value).build();
    }

    private static String toJson(Map<String, Object> headers) {
        try {
            return JSON.writeValueAsString(headers);
        } catch (Exception e) {
            throw new IllegalArgumentException("Headers are not serialisable: " + e.getMessage(), e);
        }
    }

    private static int utf8Length(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }
}
