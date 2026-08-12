package dev.christopherbell.notification;

import dev.christopherbell.notification.model.Notification;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;

public interface NotificationRepository {
  Notification save(Notification notification);
  Optional<Notification> findById(String id);
  List<Notification> findByAccountIdOrderByCreatedOnDesc(String accountId, Pageable pageable);

  long countByAccountIdAndReadFalse(String accountId);
}
