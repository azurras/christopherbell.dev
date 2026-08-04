package dev.christopherbell.view.wfl;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Immutable public-only data rendered by a restaurant profile page. */
public record RestaurantProfilePage(
    String id,
    String path,
    String canonicalUrl,
    String title,
    String description,
    String name,
    String cuisine,
    String heroMetadata,
    Address address,
    VoteSummary votes,
    String phoneNumber,
    String website,
    String sourceType,
    String directionsUrl,
    String structuredDataJson
) {
  public RestaurantProfilePage {
    requireNonBlank(id, "Restaurant id");
    requireNonBlank(path, "Restaurant path");
    requireNonBlank(canonicalUrl, "Restaurant canonical URL");
    requireNonBlank(title, "Restaurant title");
    requireNonBlank(description, "Restaurant description");
    requireNonBlank(name, "Restaurant name");
    requireNonBlank(cuisine, "Restaurant display cuisine");
    requireNonBlank(heroMetadata, "Restaurant hero metadata");
    requireNonBlank(directionsUrl, "Restaurant directions URL");
    requireNonBlank(structuredDataJson, "Restaurant structured data");
  }

  /** Returns a display address without invented placeholders or punctuation. */
  public String addressLine() {
    return address == null ? "" : address.displayLine();
  }

  /** Whether the page has a valid non-empty public vote summary. */
  public boolean hasVotes() {
    return votes != null;
  }

  /** Returns public member approval, or zero when no vote summary exists. */
  public int approvalPercentage() {
    return votes == null ? 0 : votes.approvalPercentage();
  }

  /** Public postal and optional coordinate data. */
  public record Address(
      String street1,
      String street2,
      String city,
      String state,
      String postalCode,
      String country,
      Double latitude,
      Double longitude
  ) {
    public Address {
      if ((latitude == null) != (longitude == null)) {
        throw new IllegalArgumentException("Restaurant coordinates must be present as a pair.");
      }
      if (latitude != null
          && (!Double.isFinite(latitude)
              || latitude < -90.0
              || latitude > 90.0
              || !Double.isFinite(longitude)
              || longitude < -180.0
              || longitude > 180.0)) {
        throw new IllegalArgumentException("Restaurant coordinates are outside valid ranges.");
      }
    }

    /** Returns the nonblank street, locality, region, and postal components. */
    public String displayLine() {
      return Stream.of(street1, street2, city, state, postalCode)
          .filter(value -> value != null && !value.isBlank())
          .map(String::strip)
          .collect(Collectors.joining(", "));
    }

    /** Whether this address has a complete valid coordinate pair. */
    public boolean hasCoordinates() {
      return latitude != null;
    }
  }

  /** Valid non-empty public vote summary. */
  public record VoteSummary(int upVotes, int downVotes, int voteCount) {
    public VoteSummary {
      if (upVotes < 0 || downVotes < 0 || voteCount <= 0 || upVotes + downVotes != voteCount) {
        throw new IllegalArgumentException("Restaurant vote summary is invalid.");
      }
    }

    /** Returns the nearest whole percent of public member approval. */
    public int approvalPercentage() {
      return (int) Math.round(upVotes * 100.0 / voteCount);
    }
  }

  private static void requireNonBlank(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " is required.");
    }
  }
}
