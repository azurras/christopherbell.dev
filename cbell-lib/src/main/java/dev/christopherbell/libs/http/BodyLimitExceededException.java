package dev.christopherbell.libs.http;

import java.io.IOException;

/** Signals that a remote response exceeded its caller-declared byte contract. */
public final class BodyLimitExceededException extends IOException {
  public BodyLimitExceededException(long maximumBytes) {
    super("Remote response exceeded the " + maximumBytes + " byte limit.");
  }
}
