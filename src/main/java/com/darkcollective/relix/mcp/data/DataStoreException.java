package com.darkcollective.relix.mcp.data;

/**
 * A {@link DataStore} could not do what it was asked: the service holding the data failed
 * or could not be reached. A tool answers with this rather than failing the call.
 */
public final class DataStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message what went wrong
     * @param cause   what it went wrong with, or null
     */
    public DataStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
