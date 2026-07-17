package dev.christopherbell.sharedfolder.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Reports one invalid byte-range request without disclosing a filesystem path. */
public final class SharedFolderRangeNotSatisfiableException extends ResponseStatusException {
  private final long totalLength;

  /** Creates a 416 response with the known complete resource length. */
  public SharedFolderRangeNotSatisfiableException(long totalLength) {
    super(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "Requested byte range is not satisfiable");
    this.totalLength = totalLength;
  }

  /** Returns the safe resource length used in the HTTP {@code Content-Range} response. */
  public long totalLength() {
    return totalLength;
  }
}
