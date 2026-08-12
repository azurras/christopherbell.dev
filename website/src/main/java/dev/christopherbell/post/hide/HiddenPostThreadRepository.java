package dev.christopherbell.post.hide;

import java.util.List;
import java.util.Optional;

/** Persistence boundary for per-user hidden post threads. */
public interface HiddenPostThreadRepository {
  HiddenPostThread save(HiddenPostThread hiddenThread);
  Optional<HiddenPostThread> findByAccountIdAndRootPostId(String accountId, String rootPostId);

  List<HiddenPostThread> findByAccountId(String accountId);

  void deleteByAccountIdAndRootPostId(String accountId, String rootPostId);
}
