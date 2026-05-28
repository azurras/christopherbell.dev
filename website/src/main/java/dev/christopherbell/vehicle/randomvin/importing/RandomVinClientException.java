package dev.christopherbell.vehicle.randomvin.importing;

/**
 * Exception for non-success RandomVIN HTTP responses.
 */
public class RandomVinClientException extends Exception {
  private final int statusCode;

  /**
   * Creates an exception for a non-success RandomVIN HTTP status.
   *
   * @param statusCode the HTTP status returned by RandomVIN
   */
  public RandomVinClientException(int statusCode) {
    super("RandomVIN returned HTTP status " + statusCode);
    this.statusCode = statusCode;
  }

  /**
   * Gets the HTTP status returned by RandomVIN.
   *
   * @return the HTTP status code
   */
  public int getStatusCode() {
    return statusCode;
  }
}
