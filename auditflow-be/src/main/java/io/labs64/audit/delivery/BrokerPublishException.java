package io.labs64.audit.delivery;

/** The broker did not confirm a publish (nack, unroutable, timeout or transport error). */
public class BrokerPublishException extends RuntimeException {

    public BrokerPublishException(String message) {
        super(message);
    }
}
