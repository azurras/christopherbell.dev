package dev.christopherbell.post.hide;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence boundary for per-user hidden post threads. */
public interface HiddenPostThreadRepository extends MongoRepository<HiddenPostThread, String> {
  Optional<HiddenPostThread> findByAccountIdAndRootPostId(String accountId, String rootPostId);

  List<HiddenPostThread> findByAccountId(String accountId);

  void deleteByAccountIdAndRootPostId(String accountId, String rootPostId);
}
