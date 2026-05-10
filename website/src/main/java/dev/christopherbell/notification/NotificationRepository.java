package dev.christopherbell.notification;

import dev.christopherbell.notification.model.Notification;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationRepository extends MongoRepository<Notification, String> {
  List<Notification> findByAccountIdOrderByCreatedOnDesc(String accountId, Pageable pageable);

  long countByAccountIdAndReadFalse(String accountId);
}
