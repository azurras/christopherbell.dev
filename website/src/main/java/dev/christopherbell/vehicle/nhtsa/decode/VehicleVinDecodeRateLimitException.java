package dev.christopherbell.vehicle.nhtsa.decode;

public class VehicleVinDecodeRateLimitException extends RuntimeException {
  public VehicleVinDecodeRateLimitException(String message) {
    super(message);
  }
}
