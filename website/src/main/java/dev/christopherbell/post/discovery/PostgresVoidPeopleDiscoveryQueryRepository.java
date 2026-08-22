package dev.christopherbell.post.discovery;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.post.model.PostTopic;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL active-post query used by privacy-aware people discovery. */
@PostgresPersistence
public class PostgresVoidPeopleDiscoveryQueryRepository implements VoidPeopleDiscoveryQueryPort {
  private static final int MAX_INTEREST_POSTS = 256;
  private static final int MAX_CANDIDATES = 128;
  private final JdbcClient database;
  private final String postTable;
  private final String likeTable;
  private final String topicTable;

  public PostgresVoidPeopleDiscoveryQueryRepository(
      JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    postTable = schemas.qualifiedTable("social", "post");
    likeTable = schemas.qualifiedTable("social", "post_like");
    topicTable = schemas.qualifiedTable("social", "post_topic");
  }

  @Override
  public Set<String> interestsFor(String accountId, Instant now) {
    var values = database.sql("""
            select topic.canonical from %1$s topic
            join %2$s post on post.post_id = topic.post_id
            where topic.post_id in (
              select candidate.post_id from %2$s candidate
              where candidate.expires_on > :now
                and (candidate.account_id = :accountId or candidate.post_id in (
                  select liked.post_id from %3$s liked where liked.account_id = :accountId))
              order by candidate.created_on desc, candidate.post_id desc limit :limit)
            order by post.created_on desc, post.post_id desc, topic.ordinal asc
            """.formatted(topicTable, postTable, likeTable))
        .param("now", now.atOffset(ZoneOffset.UTC)).param("accountId", accountId)
        .param("limit", MAX_INTEREST_POSTS).query(String.class).list();
    return Set.copyOf(new LinkedHashSet<>(values));
  }

  @Override
  public List<VoidPersonCandidate> recentActiveCandidates(Instant now, int requestedLimit) {
    int limit = Math.max(1, Math.min(requestedLimit, MAX_CANDIDATES));
    var candidates = database.sql("""
            select account_id, max(coalesce(last_extended_on, created_on)) as activity
            from %s where expires_on > :now group by account_id
            order by activity desc, account_id asc limit :limit
            """.formatted(postTable))
        .param("now", now.atOffset(ZoneOffset.UTC)).param("limit", limit)
        .query((row, ignored) -> new CandidateRow(
            row.getString("account_id"),
            row.getObject("activity", OffsetDateTime.class).toInstant()))
        .list();
    if (candidates.isEmpty()) return List.of();
    var ids = candidates.stream().map(CandidateRow::accountId).toList();
    var topics = new LinkedHashMap<String, LinkedHashMap<String, PostTopic>>();
    database.sql("""
            select post.account_id, topic.canonical, topic.display
            from %1$s post join %2$s topic on topic.post_id = post.post_id
            where post.account_id in (:ids) and post.expires_on > :now
            order by post.account_id asc, topic.canonical asc, topic.display asc
            """.formatted(postTable, topicTable))
        .param("ids", ids).param("now", now.atOffset(ZoneOffset.UTC))
        .query((row, ignored) -> new TopicRow(
            row.getString(1), row.getString(2), row.getString(3)))
        .list().forEach(row -> topics.computeIfAbsent(
            row.accountId(), ignored -> new LinkedHashMap<>())
            .putIfAbsent(row.canonical(), new PostTopic(row.canonical(), row.display())));
    var result = new ArrayList<VoidPersonCandidate>(candidates.size());
    for (var candidate : candidates) {
      var accountTopics = topics.get(candidate.accountId());
      result.add(new VoidPersonCandidate(
          candidate.accountId(),
          accountTopics == null ? List.of() : List.copyOf(accountTopics.values()),
          candidate.activity()));
    }
    return List.copyOf(result);
  }

  private record CandidateRow(String accountId, Instant activity) {}
  private record TopicRow(String accountId, String canonical, String display) {}
}
