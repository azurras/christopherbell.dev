package dev.christopherbell.whatsforlunch.restaurant.session;

import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSession;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;

/** Stores shared What's For Lunch voting sessions. */
public interface WhatsForLunchSessionRepository {
  WhatsForLunchSession save(WhatsForLunchSession session);
  Optional<WhatsForLunchSession> findById(String id);
  List<WhatsForLunchSession>
      findByParticipantAccountIdsContainingAndDeleteOnAfterOrderByCreatedOnDesc(
      String accountId,
      Instant now,
      Pageable pageable
  );
}
