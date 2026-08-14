package dev.christopherbell.configuration.mongo.domain;

/** Non-sensitive Mongo duplicate-key diagnostic retained across exception translation. */
public final class SanitizedMongoDuplicateKeyCause extends RuntimeException {
  private final int errorCode;

  SanitizedMongoDuplicateKeyCause(int errorCode) {
    super("MongoDB duplicate key conflict (code " + errorCode + ").", null, false, true);
    this.errorCode = errorCode;
  }

  public int errorCode() {
    return errorCode;
  }
}
