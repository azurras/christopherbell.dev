package dev.christopherbell.vehicle.model;

import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Ordered VINs submitted for partial-success decoding. */
public record VehicleVinDecodeBatchRequest(@NotNull List<String> vins) {
  public VehicleVinDecodeBatchRequest {
    if (vins != null) {
      vins = Collections.unmodifiableList(new ArrayList<>(vins));
    }
  }
}
