package dev.christopherbell.vehicle.model;

import java.util.List;

/** Aggregate counts and ordered results for a VIN batch decode. */
public record VehicleVinDecodeBatchResponse(
    int submittedCount,
    int successCount,
    int errorCount,
    List<VehicleVinDecodeBatchEntry> results
) {
  public VehicleVinDecodeBatchResponse {
    results = List.copyOf(results);
    if (submittedCount != results.size()
        || successCount + errorCount != submittedCount
        || successCount != successCountOf(results)) {
      throw new IllegalArgumentException("VIN batch counts must agree with ordered results.");
    }
  }

  public static VehicleVinDecodeBatchResponse from(List<VehicleVinDecodeBatchEntry> results) {
    var immutableResults = List.copyOf(results);
    var successCount = successCountOf(immutableResults);
    return new VehicleVinDecodeBatchResponse(
        immutableResults.size(),
        successCount,
        immutableResults.size() - successCount,
        immutableResults);
  }

  private static int successCountOf(List<VehicleVinDecodeBatchEntry> results) {
    return (int) results.stream()
        .filter(entry -> VehicleVinDecodeBatchEntry.SUCCESS.equals(entry.status()))
        .count();
  }
}
