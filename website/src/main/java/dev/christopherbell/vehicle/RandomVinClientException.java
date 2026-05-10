package dev.christopherbell.vehicle;

/**
 * Exception for non-success RandomVIN HTTP responses.
 */
public class RandomVinClientException extends Exception {
  private final int statusCode;

  public RandomVinClientException(int statusCode) {
    super("RandomVIN returned HTTP status " + statusCode);
    this.statusCode = statusCode;
  }

  public int getStatusCode() {
    return statusCode;
  }
}
