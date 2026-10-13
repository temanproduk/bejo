package com.bejo.notification;

/** The mail server refused or could not be reached. The message never contains credentials. */
public class EmailDeliveryException extends RuntimeException {
    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
