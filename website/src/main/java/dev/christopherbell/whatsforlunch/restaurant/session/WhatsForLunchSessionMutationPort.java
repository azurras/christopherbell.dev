package dev.christopherbell.whatsforlunch.restaurant.session;

import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionRestaurantsRequest;
import java.time.Instant;

/** Atomic persistence boundary for targeted shared-session mutations. */
public interface WhatsForLunchSessionMutationPort {
  WhatsForLunchSessionMutationStore.Result join(
      String sessionId, String accountId, String username, Instant now, int maxMembers);

  WhatsForLunchSessionMutationStore.Result vote(
      String sessionId, String accountId, String restaurantId, Instant now);

  WhatsForLunchSessionMutationStore.Result resetRestaurants(
      String sessionId,
      String accountId,
      String username,
      WhatsForLunchSessionRestaurantsRequest request,
      Instant now);
}
