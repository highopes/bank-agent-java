package com.demo.bankagent.observability;

/** Invalid observability / Agent Control configuration. The message never contains credential values. */
public class AoConfigurationException extends RuntimeException {

    public AoConfigurationException(String message) {
        super(message);
    }

    public AoConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
