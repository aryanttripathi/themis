package io.themis.config;

/** Invalid configuration (unknown key, bad value, unreadable file); the CLI reports it with exit code 2. */
public final class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
