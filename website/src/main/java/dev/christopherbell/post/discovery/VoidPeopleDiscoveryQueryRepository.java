package dev.christopherbell.post.discovery;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedAggregation;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import dev.christopherbell.post.api.PostExpirationAggregation;
import dev.christopherbell.post.like.PostLikeStore;
import dev.christopherbell.post.model.Post;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.bson.Document;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;

/** Bounded active-post queries used to suggest people without popularity signals. */
@MongoPersistence
@Repository
public class VoidPeopleDiscoveryQueryRepository implements VoidPeopleDiscoveryQueryPort {
  private static final int MAX_INTEREST_POSTS = 256;
  private static final int MAX_CANDIDATES = 128;

  private final KindScopedMongoOperations<Post> posts;
  private final PostLikeStore likes;

  public VoidPeopleDiscoveryQueryRepository(
      DomainMongoOperationsFactory factory, PostLikeStore likes) {
    this.posts = factory.forType(Post.class);
    this.likes = likes;
  }

  @Override
  public Set<String> interestsFor(String accountId, Instant now) {
    var participation = new Criteria().orOperator(
        Criteria.where("accountId").is(accountId),
        Criteria.where("id").in(likes.recentLikedPostIds(accountId)));
    var stages = new ArrayList<AggregationOperation>();
    stages.add(context -> new Document("$match", participation.getCriteriaObject()));
    stages.addAll(PostExpirationAggregation.activeAt(now));
    stages.add(context -> new Document("$sort", new Document("createdOn", -1).append("_id", -1)));
    stages.add(context -> new Document("$limit", MAX_INTEREST_POSTS));
    var interests = new LinkedHashSet<String>();
    posts.aggregate(KindScopedAggregation.withForeignKinds(
        Aggregation.newAggregation(stages), KindScopedAggregation.ForeignKind.POST), Post.class).stream()
        .flatMap(post -> post.getTopics() == null ? Stream.empty() : post.getTopics().stream())
        .filter(Objects::nonNull)
        .map(topic -> topic.canonical())
        .forEach(interests::add);
    return Set.copyOf(interests);
  }

  @Override
  public List<VoidPersonCandidate> recentActiveCandidates(
      Instant now, int requestedLimit) {
    int limit = Math.max(1, Math.min(requestedLimit, MAX_CANDIDATES));
    var stages = new ArrayList<AggregationOperation>();
    stages.add(context -> new Document("$match", new Document("accountId", new Document("$type", "string"))));
    stages.addAll(PostExpirationAggregation.activeAt(now));
    stages.addAll(List.of(
        context -> new Document("$project", new Document("accountId", 1)
            .append("topics", 1)
            .append("recentActivityOn", new Document(
                "$ifNull", List.of("$lastExtendedOn", "$createdOn")))),
        context -> new Document("$unwind", new Document("path", "$topics")
            .append("preserveNullAndEmptyArrays", true)),
        context -> new Document("$group", new Document("_id", "$accountId")
            .append("topics", new Document("$addToSet", "$topics"))
            .append("recentActivityOn", new Document("$max", "$recentActivityOn"))),
        context -> new Document("$sort", new Document("recentActivityOn", -1).append("_id", 1)),
        context -> new Document("$limit", limit),
        context -> new Document("$project", new Document("_id", 0)
            .append("accountId", "$_id")
            .append("topics", 1)
            .append("recentActivityOn", 1))));
    return posts.aggregate(KindScopedAggregation.withForeignKinds(
        Aggregation.newAggregation(stages), KindScopedAggregation.ForeignKind.POST), VoidPersonCandidate.class);
  }
}
