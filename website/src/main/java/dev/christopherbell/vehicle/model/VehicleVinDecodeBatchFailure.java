package dev.christopherbell.vehicle.model;

/**
 * Why one VIN in a batch decode has no result. The name is the published error code and status.
 */
public enum VehicleVinDecodeBatchFailure {
  INVALID_VIN("VIN must be 17 valid VIN characters."),
  CACHE_UNAVAILABLE("VIN cache is temporarily unavailable."),
  UPSTREAM_UNAVAILABLE("VIN decoding is temporarily unavailable. Please try again later."),
  UPSTREAM_NO_RESULT("NHTSA returned no result for this VIN.");

  private final String message;

  VehicleVinDecodeBatchFailure(String message) {
    this.message = message;
  }

  /** The safe message published with this failure. */
  public String message() {
    return message;
  }
}
