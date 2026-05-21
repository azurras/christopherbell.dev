package dev.christopherbell.whatsforlunch.restaurant.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Builder;

/** API response for a shared WFL session without exposing participant account ids. */
@Builder
public record WhatsForLunchSessionDetail(
    String id,
    String createdByUsername,
    List<String> participantUsernames,
    List<RestaurantDetail> restaurants,
    Map<String, List<String>> votesByRestaurant,
    String myVoteRestaurantId,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    Instant createdOn,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    Instant lastUpdatedOn
) {}
