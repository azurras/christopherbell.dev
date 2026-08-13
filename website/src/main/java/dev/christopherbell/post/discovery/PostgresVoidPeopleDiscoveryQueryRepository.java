package dev.christopherbell.post.discovery;

import static dev.christopherbell.persistence.jooq.social.Tables.POST;
import static dev.christopherbell.persistence.jooq.social.Tables.POST_LIKE;
import static dev.christopherbell.persistence.jooq.social.Tables.POST_TOPIC;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.post.model.PostTopic;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** PostgreSQL active-post query used by privacy-aware people discovery. */
@PostgresPersistence
public final class PostgresVoidPeopleDiscoveryQueryRepository
    implements VoidPeopleDiscoveryQueryPort {
  private static final int MAX_INTEREST_POSTS = 256;
  private static final int MAX_CANDIDATES = 128;
  private final DSLContext database;

  public PostgresVoidPeopleDiscoveryQueryRepository(DSLContext database) {
    this.database = database;
  }

  @Override
  public Set<String> interestsFor(String accountId, Instant now) {
    var participatingPostIds = DSL.select(POST.POST_ID)
        .from(POST)
        .where(POST.EXPIRES_ON.gt(now.atOffset(ZoneOffset.UTC))
            .and(POST.ACCOUNT_ID.eq(accountId).or(POST.POST_ID.in(
                DSL.select(POST_LIKE.POST_ID).from(POST_LIKE)
                    .where(POST_LIKE.ACCOUNT_ID.eq(accountId))))))
        .orderBy(POST.CREATED_ON.desc(), POST.POST_ID.desc())
        .limit(MAX_INTEREST_POSTS);
    var values = database.select(POST_TOPIC.CANONICAL)
        .from(POST_TOPIC)
        .join(POST).on(POST.POST_ID.eq(POST_TOPIC.POST_ID))
        .where(POST_TOPIC.POST_ID.in(participatingPostIds))
        .orderBy(POST.CREATED_ON.desc(), POST.POST_ID.desc(), POST_TOPIC.ORDINAL.asc())
        .fetch(POST_TOPIC.CANONICAL);
    return Set.copyOf(new LinkedHashSet<>(values));
  }

  @Override
  public List<VoidPersonCandidate> recentActiveCandidates(
      Instant now, int requestedLimit) {
    int limit = Math.max(1, Math.min(requestedLimit, MAX_CANDIDATES));
    var activity = DSL.max(DSL.coalesce(POST.LAST_EXTENDED_ON, POST.CREATED_ON));
    var candidates = database.select(POST.ACCOUNT_ID, activity)
        .from(POST)
        .where(POST.EXPIRES_ON.gt(now.atOffset(ZoneOffset.UTC)))
        .groupBy(POST.ACCOUNT_ID)
        .orderBy(activity.desc(), POST.ACCOUNT_ID.asc())
        .limit(limit)
        .fetch();
    if (candidates.isEmpty()) return List.of();

    var ids = candidates.getValues(POST.ACCOUNT_ID);
    var topics = new LinkedHashMap<String, LinkedHashMap<String, PostTopic>>();
    database.select(POST.ACCOUNT_ID, POST_TOPIC.CANONICAL, POST_TOPIC.DISPLAY)
        .from(POST)
        .join(POST_TOPIC).on(POST_TOPIC.POST_ID.eq(POST.POST_ID))
        .where(POST.ACCOUNT_ID.in(ids).and(POST.EXPIRES_ON.gt(now.atOffset(ZoneOffset.UTC))))
        .orderBy(POST.ACCOUNT_ID.asc(), POST_TOPIC.CANONICAL.asc(), POST_TOPIC.DISPLAY.asc())
        .forEach(record -> topics
            .computeIfAbsent(record.value1(), ignored -> new LinkedHashMap<>())
            .putIfAbsent(record.value2(), new PostTopic(record.value2(), record.value3())));
    var result = new ArrayList<VoidPersonCandidate>(candidates.size());
    for (var candidate : candidates) {
      var accountTopics = topics.get(candidate.value1());
      result.add(new VoidPersonCandidate(
          candidate.value1(),
          accountTopics == null ? List.of() : List.copyOf(accountTopics.values()),
          candidate.value2().toInstant()));
    }
    return List.copyOf(result);
  }
}
