package dev.christopherbell.vehicle.nhtsa;

/**
 * Exception for non-success NHTSA HTTP responses.
 */
public class NhtsaVinClientException extends Exception {
  private final int statusCode;

  /**
   * Creates an exception for a non-success NHTSA HTTP status.
   *
   * @param statusCode the HTTP status returned by NHTSA
   */
  public NhtsaVinClientException(int statusCode) {
    super("NHTSA returned HTTP status " + statusCode);
    this.statusCode = statusCode;
  }

  /**
   * Gets the HTTP status returned by NHTSA.
   *
   * @return the HTTP status code
   */
  public int getStatusCode() {
    return statusCode;
  }
}
