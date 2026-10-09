package dev.christopherbell.vehicle.model;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one VIN format every vehicle feature accepts: 17 letters and digits without I, O or Q.
 */
public final class VehicleVins {
  private static final Pattern VIN = Pattern.compile("^[A-HJ-NPR-Z0-9]{17}$");

  private VehicleVins() {}

  /**
   * Trims and upper-cases a submitted VIN.
   *
   * @param rawVin the VIN as submitted or returned by a source
   * @return the normalized VIN, or null when none was given
   */
  public static String normalize(String rawVin) {
    return rawVin == null ? null : rawVin.trim().toUpperCase(Locale.ROOT);
  }

  /**
   * Whether a normalized VIN has the accepted format.
   *
   * @param normalizedVin a VIN returned by {@link #normalize}
   * @return true when the VIN is exactly 17 accepted characters
   */
  public static boolean isValid(String normalizedVin) {
    return normalizedVin != null && VIN.matcher(normalizedVin).matches();
  }
}
