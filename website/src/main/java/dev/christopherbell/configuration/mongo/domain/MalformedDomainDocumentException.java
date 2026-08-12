package dev.christopherbell.configuration.mongo.domain;

import org.springframework.dao.DataIntegrityViolationException;

/** Indicates that persisted envelope data violated the trusted domain-document shape. */
public final class MalformedDomainDocumentException extends DataIntegrityViolationException {
  private static final String MESSAGE = "Mongo domain document is malformed.";

  public MalformedDomainDocumentException() {
    super(MESSAGE);
  }
}
