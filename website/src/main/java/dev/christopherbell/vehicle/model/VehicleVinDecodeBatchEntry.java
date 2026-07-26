package dev.christopherbell.vehicle.model;

/** One ordered success or safe error from a VIN batch decode. */
public record VehicleVinDecodeBatchEntry(
    int index,
    String submittedVin,
    String normalizedVin,
    String status,
    VehicleVinDecodeResponse decoded,
    String errorCode,
    String errorMessage
) {
  public VehicleVinDecodeBatchEntry {
    var success = "SUCCESS".equals(status);
    if (success == (decoded == null) || success == (errorCode != null || errorMessage != null)) {
      throw new IllegalArgumentException("VIN batch entry must be exactly one success or error.");
    }
  }

  public static VehicleVinDecodeBatchEntry success(
      int index, String submittedVin, String normalizedVin, VehicleVinDecodeResponse decoded) {
    return new VehicleVinDecodeBatchEntry(
        index, submittedVin, normalizedVin, "SUCCESS", decoded, null, null);
  }

  public static VehicleVinDecodeBatchEntry error(
      int index,
      String submittedVin,
      String normalizedVin,
      String errorCode,
      String errorMessage
  ) {
    return new VehicleVinDecodeBatchEntry(
        index, submittedVin, normalizedVin, errorCode, null, errorCode, errorMessage);
  }
}
