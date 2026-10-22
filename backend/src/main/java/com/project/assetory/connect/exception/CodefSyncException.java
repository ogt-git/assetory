package com.project.assetory.connect.exception;

public class CodefSyncException extends RuntimeException {

    public CodefSyncException(String message) {
        super(message);
    }

    public CodefSyncException(String message, Throwable cause) {
        super(message, cause);
    }
}
