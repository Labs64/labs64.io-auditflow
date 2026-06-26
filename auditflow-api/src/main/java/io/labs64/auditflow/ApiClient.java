package io.labs64.auditflow;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Compile-time stub satisfying {@code toUrlQueryString()} references emitted by the
 * openapi-generator native library template. Delete this class if generateSupportingFiles
 * is ever re-enabled and the generator produces a real ApiClient.java.
 *
 * <p>Minimal stub providing static utilities required by generated model classes.
 * The full ApiClient (HTTP transport) is in the client sub-package and is a
 * separate concern; these two static helpers are referenced by
 * {@code toUrlQueryString()} in each generated model.
 */
public class ApiClient {

    private ApiClient() {}

    public static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public static String valueToString(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString();
    }
}
