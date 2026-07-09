package dev.christopherbell.vehicle.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VehicleVinDecodeRequest(
    @NotBlank
    @Pattern(regexp = "^[A-HJ-NPR-Za-hj-npr-z0-9]{17}$")
    String vin
) {}
