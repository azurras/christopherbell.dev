package dev.christopherbell.configuration.filter;

import java.io.IOException;

/** Recognizable streaming overflow that survives controller/service boundaries for HTTP 413 mapping. */
public final class RequestPayloadTooLargeException extends IOException {
}
