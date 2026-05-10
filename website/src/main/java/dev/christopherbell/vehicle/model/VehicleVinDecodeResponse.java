package dev.christopherbell.vehicle.model;

import java.util.Map;
import lombok.Builder;

@Builder
public record VehicleVinDecodeResponse(
    String vin,
    String make,
    String model,
    Integer year,
    String body,
    String plantCity,
    String plantState,
    String plantCountry,
    String errorCode,
    String errorText,
    Map<String, String> rawDecodedValues
) {}
