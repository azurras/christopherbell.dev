package dev.christopherbell.location.model;

import lombok.Builder;

/**
 * Public ZIP coordinate response payload.
 *
 * @param zipCode five-digit ZIP Code Tabulation Area identifier
 * @param latitude representative latitude
 * @param longitude representative longitude
 * @param source coordinate source name
 * @param sourceYear coordinate source year
 */
@Builder
public record ZipCoordinateDetail(
    String zipCode,
    double latitude,
    double longitude,
    String source,
    int sourceYear
) {}
