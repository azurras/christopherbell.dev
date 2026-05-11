package dev.christopherbell.message;

import dev.christopherbell.message.model.Message;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageRepository extends MongoRepository<Message, String> {
  List<Message> findByConversationKeyOrderByCreatedOnAsc(String conversationKey, Pageable pageable);

  List<Message> findByParticipantIdsContainingOrderByCreatedOnDesc(String accountId, Pageable pageable);

  long countByRecipientAccountIdAndSenderAccountIdAndReadFalse(String recipientAccountId, String senderAccountId);
}
