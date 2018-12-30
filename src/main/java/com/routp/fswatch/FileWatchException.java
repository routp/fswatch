package com.routp.fswatch;

/**
 * The exception class for file system watch
 */
public class FileWatchException extends Exception {
    public FileWatchException() {
        super();
    }

    public FileWatchException(final Exception cause) {
        super(cause);
    }

    public FileWatchException(final String message) {
        super(message);
    }

    public FileWatchException(final String message, final Exception cause) {
        super(message, cause);
    }
}
