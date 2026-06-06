package dev.christopherbell.canesboxtracker.model;

import java.math.BigDecimal;

/**
 * Admin-entered Box Combo price from a manually checked source.
 *
 * @param metroName configured metro receiving the verified price
 * @param price pre-tax Box Combo menu price
 * @param sourceUrl evidence URL used for verification
 * @param note review note describing the evidence
 */
public record CanesBoxManualPriceRequest(
    String metroName,
    BigDecimal price,
    String sourceUrl,
    String note
) {}
