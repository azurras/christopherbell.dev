package dev.christopherbell.canesboxtracker.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Public weekly Raising Canes Box Index price detail.
 *
 * @param weekStartDate Monday date for the tracked week
 * @param collectedOn when the collector stored the snapshot
 * @param averagePrice average pre-tax Box Combo price across verified metros
 * @param currency price currency
 * @param successfulMetroCount number of metros that returned any price
 * @param totalMetroCount number of configured metros attempted
 * @param verifiedMetroCount number of metros included in the public index
 * @param provisionalMetroCount number of unverified public-menu datapoints
 * @param excludedMetroCount number of failed or rejected datapoints
 * @param metroPrices per-metro price or failure details
 */
public record CanesBoxWeeklyPriceDetail(
    String weekStartDate,
    Instant collectedOn,
    BigDecimal averagePrice,
    String currency,
    int successfulMetroCount,
    int totalMetroCount,
    int verifiedMetroCount,
    int provisionalMetroCount,
    int excludedMetroCount,
    List<CanesBoxMetroPrice> metroPrices
) {}
