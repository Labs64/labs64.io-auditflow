package io.labs64.audit.broker;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A message as the application sees it, whatever broker carries it: a JSON body, string-keyed
 * headers (strings and numbers) and an optional application message id. Adapters translate it to
 * their own format (AMQP properties, SQS message attributes) and back.
 */
public final class BrokerMessage {

    private final byte[] body;
    private final Map<String, Object> headers;
    private final String messageId;

    public BrokerMessage(byte[] body, Map<String, Object> headers, String messageId) {
        this.body = body;
        this.headers = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
        this.messageId = messageId;
    }

    public static BrokerMessage of(String body) {
        return new BrokerMessage(body.getBytes(StandardCharsets.UTF_8), null, null);
    }

    public byte[] body() {
        return body;
    }

    public String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /** Mutable view, for building a message; published messages are not changed afterwards. */
    public Map<String, Object> headers() {
        return headers;
    }

    public Object header(String name) {
        return headers.get(name);
    }

    public BrokerMessage withHeader(String name, Object value) {
        if (value == null) {
            headers.remove(name);
        } else {
            headers.put(name, value);
        }
        return this;
    }

    public String messageId() {
        return messageId;
    }
}
