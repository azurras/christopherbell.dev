package dev.christopherbell.whatsforlunch.restaurant.session;

import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSession;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/** Stores shared What's For Lunch voting sessions. */
@Repository
public interface WhatsForLunchSessionRepository extends MongoRepository<WhatsForLunchSession, String> {
  List<WhatsForLunchSession>
      findByParticipantAccountIdsContainingAndDeleteOnAfterOrderByCreatedOnDesc(
      String accountId,
      Instant now,
      Pageable pageable
  );
}
