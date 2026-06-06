package dev.christopherbell.canesboxtracker.model;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Stored price result for one metro in one weekly Raising Canes Box Index run.
 */
@AllArgsConstructor
@Data
@NoArgsConstructor
public class CanesBoxMetroPrice {
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
   * Creates a successful metro price result from a configured target.
   */
  public static CanesBoxMetroPrice success(
      CanesBoxTrackerProperties.MetroTarget target,
      BigDecimal price,
      Instant collectedOn
  ) {
    return success(target, price, collectedOn, "OFFICIAL_API", target.getSourceUrl());
  }

  /**
   * Creates a successful metro price result with source metadata.
   */
  public static CanesBoxMetroPrice success(
      CanesBoxTrackerProperties.MetroTarget target,
      BigDecimal price,
      Instant collectedOn,
      String sourceName,
      String sourceUrl
  ) {
    var result = fromTarget(target);
    result.setPrice(price);
    result.setCurrency("USD");
    result.setStatus("SUCCESS");
    result.setSourceName(sourceName);
    result.setSourceUrl(sourceUrl);
    result.setQualityStatus(qualityStatusForSource(sourceName));
    result.setConfidenceLevel(confidenceForSource(sourceName));
    result.setMatchedItemName("The Box Combo");
    result.setCollectedOn(collectedOn);
    result.setSourceFetchedOn(collectedOn);
    return result;
  }

  /**
   * Creates a failed metro price result while preserving the source target.
   */
  public static CanesBoxMetroPrice failure(
      CanesBoxTrackerProperties.MetroTarget target,
      String failureReason
  ) {
    var result = fromTarget(target);
    result.setCurrency("USD");
    result.setStatus("FAILED");
    result.setSourceName("NONE");
    result.setQualityStatus("EXCLUDED");
    result.setConfidenceLevel("NONE");
    result.setFailureReason(failureReason);
    var collectedOn = Instant.now();
    result.setCollectedOn(collectedOn);
    result.setSourceFetchedOn(collectedOn);
    return result;
  }

  /**
   * Marks this datapoint as reviewed and index-eligible.
   */
  public void verify(String note) {
    setQualityStatus("VERIFIED");
    setConfidenceLevel("HIGH");
    setReviewNote(note);
    setReviewedOn(Instant.now());
  }

  /**
   * Marks this datapoint as reviewed and excluded from index calculations.
   */
  public void exclude(String note) {
    setQualityStatus("EXCLUDED");
    setConfidenceLevel("NONE");
    setReviewNote(note);
    setReviewedOn(Instant.now());
  }

  private static CanesBoxMetroPrice fromTarget(CanesBoxTrackerProperties.MetroTarget target) {
    var result = new CanesBoxMetroPrice();
    result.setMetroName(target.getMetroName());
    result.setCity(target.getCity());
    result.setState(target.getState());
    result.setRestaurantRef(target.getRestaurantRef());
    result.setRestaurantName(target.getRestaurantName());
    result.setAddress(target.getAddress());
    result.setSourceUrl(target.getSourceUrl());
    return result;
  }

  private static String qualityStatusForSource(String sourceName) {
    if ("OFFICIAL_API".equals(sourceName) || "MANUAL_VERIFIED".equals(sourceName)) {
      return "VERIFIED";
    }
    if ("PUBLIC_MENU".equals(sourceName)) {
      return "PROVISIONAL";
    }
    return "EXCLUDED";
  }

  private static String confidenceForSource(String sourceName) {
    if ("OFFICIAL_API".equals(sourceName) || "MANUAL_VERIFIED".equals(sourceName)) {
      return "HIGH";
    }
    if ("PUBLIC_MENU".equals(sourceName)) {
      return "LOW";
    }
    return "NONE";
  }
}
