package dev.christopherbell.configuration.mongo.domain;

import org.springframework.dao.InvalidDataAccessApiUsageException;

/** Indicates that a domain query or update attempted an unapproved field or operation. */
public final class UnapprovedDomainFieldException extends InvalidDataAccessApiUsageException {
  public UnapprovedDomainFieldException() {
    super("Mongo domain field is not approved.");
  }
}
