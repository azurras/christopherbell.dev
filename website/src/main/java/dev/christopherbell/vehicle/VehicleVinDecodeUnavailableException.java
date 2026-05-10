package dev.christopherbell.vehicle;

public class VehicleVinDecodeUnavailableException extends RuntimeException {
  public VehicleVinDecodeUnavailableException(String message) {
    super(message);
  }

  public VehicleVinDecodeUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
