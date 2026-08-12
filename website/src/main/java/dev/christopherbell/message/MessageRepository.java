package dev.christopherbell.message;

import dev.christopherbell.message.model.Message;
import java.util.List;
import org.springframework.data.domain.Pageable;

public interface MessageRepository {
  Message save(Message message);
  Iterable<Message> saveAll(Iterable<Message> messages);
  List<Message> findByConversationKeyOrderByCreatedOnAsc(String conversationKey, Pageable pageable);

  List<Message> findByParticipantIdsContainingOrderByCreatedOnDesc(String accountId, Pageable pageable);
}
