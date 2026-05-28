package dev.christopherbell.vehicle.nhtsa.decode;

public class VehicleVinDecodeUnavailableException extends RuntimeException {
  public VehicleVinDecodeUnavailableException(String message) {
    super(message);
  }

  public VehicleVinDecodeUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
