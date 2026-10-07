package dev.christopherbell.canesboxtracker.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Stored price result for one metro in one weekly Raising Canes Box Index run.
 *
 * <p>{@code status}, {@code qualityStatus}, {@code confidenceLevel} and {@code sourceName} stay
 * strings because they are the stored and published values; code reads them through
 * {@link #hasCollectedPrice()}, {@link #effectiveQuality()} and {@link #isFromSource} and writes
 * them only from the enums.</p>
 */
@AllArgsConstructor
@Data
@NoArgsConstructor
public class CanesBoxMetroPrice {
  private static final String USD = "USD";
  private static final String BOX_COMBO_ITEM_NAME = "The Box Combo";

  private String metroName;
  private String city;
  private String state;
  private String restaurantRef;
  private String restaurantName;
  private String address;
  private String sourceUrl;
  private BigDecimal price;
  private String currency;
  private String status;
  private String sourceName;
  private String qualityStatus;
  private String confidenceLevel;
  private String rawResponseHash;
  private String matchedItemName;
  private String failureReason;
  private String reviewNote;
  private Instant collectedOn;
  private Instant sourceFetchedOn;
  private Instant reviewedOn;

  /**
   * Creates a successful price from the official ordering API.
   */
  public static CanesBoxMetroPrice success(
      CanesBoxTrackerProperties.MetroTarget target, BigDecimal price, Instant collectedOn) {
    return success(target, price, collectedOn, CanesBoxPriceSource.OFFICIAL_API, target.getSourceUrl());
  }

  /**
   * Creates a successful price whose initial quality and confidence come from its source.
   */
  public static CanesBoxMetroPrice success(
      CanesBoxTrackerProperties.MetroTarget target,
      BigDecimal price,
      Instant collectedOn,
      CanesBoxPriceSource source,
      String sourceUrl) {
    CanesBoxMetroPrice collectedPrice = fromTarget(target);
    collectedPrice.setPrice(price);
    collectedPrice.setCurrency(USD);
    collectedPrice.setStatus(CanesBoxPriceStatus.SUCCESS.name());
    collectedPrice.setSourceName(source.name());
    collectedPrice.setSourceUrl(sourceUrl);
    collectedPrice.setQualityStatus(source.initialQuality().name());
    collectedPrice.setConfidenceLevel(source.initialConfidence().name());
    collectedPrice.setMatchedItemName(BOX_COMBO_ITEM_NAME);
    collectedPrice.setCollectedOn(collectedOn);
    collectedPrice.setSourceFetchedOn(collectedOn);
    return collectedPrice;
  }

  /**
   * Creates a failed, excluded price that keeps the configured target for display.
   */
  public static CanesBoxMetroPrice failure(
      CanesBoxTrackerProperties.MetroTarget target, String failureReason, Instant failedOn) {
    CanesBoxMetroPrice failedPrice = fromTarget(target);
    failedPrice.setCurrency(USD);
    failedPrice.setStatus(CanesBoxPriceStatus.FAILED.name());
    failedPrice.setSourceName(CanesBoxPriceSource.NONE.name());
    failedPrice.setQualityStatus(CanesBoxPriceQuality.EXCLUDED.name());
    failedPrice.setConfidenceLevel(CanesBoxPriceConfidence.NONE.name());
    failedPrice.setFailureReason(failureReason);
    failedPrice.setCollectedOn(failedOn);
    failedPrice.setSourceFetchedOn(failedOn);
    return failedPrice;
  }

  /** Creates a detached copy with a response-safe failure reason. */
  public CanesBoxMetroPrice copyWithFailureReason(String publicFailureReason) {
    return new CanesBoxMetroPrice(
        metroName, city, state, restaurantRef, restaurantName, address, sourceUrl, price,
        currency, status, sourceName, qualityStatus, confidenceLevel, rawResponseHash,
        matchedItemName, publicFailureReason, reviewNote, collectedOn, sourceFetchedOn, reviewedOn);
  }

  /** Marks this price as reviewed and index-eligible. */
  public void verify(String note, Instant reviewedOn) {
    setQualityStatus(CanesBoxPriceQuality.VERIFIED.name());
    setConfidenceLevel(CanesBoxPriceConfidence.HIGH.name());
    setReviewNote(note);
    setReviewedOn(reviewedOn);
  }

  /** Marks this price as reviewed and excluded from index calculations. */
  public void exclude(String note, Instant reviewedOn) {
    setQualityStatus(CanesBoxPriceQuality.EXCLUDED.name());
    setConfidenceLevel(CanesBoxPriceConfidence.NONE.name());
    setReviewNote(note);
    setReviewedOn(reviewedOn);
  }

  /** Turns a price that failed a plausibility check into an excluded failure, keeping the reason. */
  public void excludeAsImplausible(String failureReason) {
    setFailureReason(failureReason);
    setPrice(null);
    setStatus(CanesBoxPriceStatus.FAILED.name());
    setQualityStatus(CanesBoxPriceQuality.EXCLUDED.name());
    setConfidenceLevel(CanesBoxPriceConfidence.NONE.name());
  }

  /** Whether collection succeeded and produced a price. */
  public boolean hasCollectedPrice() {
    return CanesBoxPriceStatus.SUCCESS.name().equals(status) && price != null;
  }

  /** Whether the stored source name is the given source. */
  public boolean isFromSource(CanesBoxPriceSource source) {
    return source.isNamed(sourceName);
  }

  /**
   * The quality used for counting and averaging. Prices stored before quality existed count as
   * verified when they have a collected price and as excluded otherwise; an unrecognized stored
   * quality also counts as excluded.
   */
  public CanesBoxPriceQuality effectiveQuality() {
    if (qualityStatus == null || qualityStatus.isBlank()) {
      return hasCollectedPrice() ? CanesBoxPriceQuality.VERIFIED : CanesBoxPriceQuality.EXCLUDED;
    }
    return Arrays.stream(CanesBoxPriceQuality.values())
        .filter(quality -> quality.name().equals(qualityStatus))
        .findFirst()
        .orElse(CanesBoxPriceQuality.EXCLUDED);
  }

  private static CanesBoxMetroPrice fromTarget(CanesBoxTrackerProperties.MetroTarget target) {
    CanesBoxMetroPrice targetPrice = new CanesBoxMetroPrice();
    targetPrice.setMetroName(target.getMetroName());
    targetPrice.setCity(target.getCity());
    targetPrice.setState(target.getState());
    targetPrice.setRestaurantRef(target.getRestaurantRef());
    targetPrice.setRestaurantName(target.getRestaurantName());
    targetPrice.setAddress(target.getAddress());
    targetPrice.setSourceUrl(target.getSourceUrl());
    return targetPrice;
  }
}
