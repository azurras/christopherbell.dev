package dev.christopherbell.canesboxtracker.model;

import java.util.Arrays;

/**
 * Where a metro price came from. The name is the stored and published {@code sourceName}; the
 * source decides the initial quality and confidence of a successful price.
 */
public enum CanesBoxPriceSource {
  OFFICIAL_API(CanesBoxPriceQuality.VERIFIED, CanesBoxPriceConfidence.HIGH),
  MANUAL_VERIFIED(CanesBoxPriceQuality.VERIFIED, CanesBoxPriceConfidence.HIGH),
  PUBLIC_MENU(CanesBoxPriceQuality.PROVISIONAL, CanesBoxPriceConfidence.LOW),
  NONE(CanesBoxPriceQuality.EXCLUDED, CanesBoxPriceConfidence.NONE);

  private final CanesBoxPriceQuality initialQuality;
  private final CanesBoxPriceConfidence initialConfidence;

  CanesBoxPriceSource(CanesBoxPriceQuality initialQuality, CanesBoxPriceConfidence initialConfidence) {
    this.initialQuality = initialQuality;
    this.initialConfidence = initialConfidence;
  }

  public CanesBoxPriceQuality initialQuality() {
    return initialQuality;
  }

  public CanesBoxPriceConfidence initialConfidence() {
    return initialConfidence;
  }

  /** Whether a stored source name names this source. */
  public boolean isNamed(String storedSourceName) {
    return name().equals(storedSourceName);
  }

  /** Parses a stored source name; unknown or missing names read as {@link #NONE}. */
  public static CanesBoxPriceSource fromStored(String storedSourceName) {
    return Arrays.stream(values())
        .filter(source -> source.isNamed(storedSourceName))
        .findFirst()
        .orElse(NONE);
  }
}
