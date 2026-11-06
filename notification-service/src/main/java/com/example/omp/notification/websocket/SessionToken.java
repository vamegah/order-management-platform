package com.example.omp.notification.websocket;

/** Keeps bearer credentials out of message/session diagnostic string rendering. */
public record SessionToken(String value) {
    @Override
    public String toString() {
        return "[redacted]";
    }
}
