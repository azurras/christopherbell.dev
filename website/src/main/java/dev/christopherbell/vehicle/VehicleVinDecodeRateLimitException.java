package dev.christopherbell.vehicle;

public class VehicleVinDecodeRateLimitException extends RuntimeException {
  public VehicleVinDecodeRateLimitException(String message) {
    super(message);
  }
}
