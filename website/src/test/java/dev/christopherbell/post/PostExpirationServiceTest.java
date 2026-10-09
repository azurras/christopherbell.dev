package dev.christopherbell.post;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.DeleteResult;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory;
import dev.christopherbell.post.expiration.MongoPostExpirationStore;
import dev.christopherbell.post.expiration.PostExpirationService;
import dev.christopherbell.post.expiration.PostExpirationStore;
import dev.christopherbell.post.model.Post;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class PostExpirationServiceTest {
  @Mock private PostRepository postRepository;
  @Mock private MongoTemplate mongo;

  @Test
  void purgeExpiredPosts_whenNoPostsNeedWork_doesNotLogStartOrCompletion(CapturedOutput output) {
    var service = new PostExpirationService(postRepository, true);
    when(postRepository.findByExpiresOnIsNull(any())).thenReturn(List.of());
    when(postRepository.findByExpiresOnLessThanEqual(
        any(), any())).thenReturn(List.of());

    service.purgeExpiredPosts();

    assertFalse(output.getOut().contains("Post expiration cleanup job started."));
    assertFalse(output.getOut().contains("Post expiration cleanup job completed."));
  }

  @Test
  void confirmedReplyImmediatelyRevivesTheRootAndSynchronizesTheThread() throws Exception {
    var createdOn = Instant.parse("2026-07-29T01:00:00Z");
    var extendedOn = Instant.parse("2026-07-29T03:00:00Z");
    var service =
        new PostExpirationService(
            postRepository,
            (PostExpirationStore) null,
            Clock.fixed(extendedOn, ZoneOffset.UTC),
            true);
    var root = Post.builder()
        .id("root")
        .rootId("root")
        .createdOn(createdOn)
        .expiresOn(createdOn.plus(Duration.ofHours(24)))
        .build();
    var reply = Post.builder()
        .id("reply")
        .rootId("root")
        .parentId("root")
        .expiresOn(root.getExpiresOn())
        .build();
    when(postRepository.findById(eq("root"))).thenReturn(Optional.of(root));
    when(postRepository.findByRootIdOrderByCreatedOnAsc(eq("root")))
        .thenReturn(List.of(root, reply));

    service.refreshThreadRootExpirationForNewReply(reply, extendedOn);

    assertEquals(extendedOn, root.getLastExtendedOn());
    assertEquals(createdOn.plus(Duration.ofHours(48)), root.getExpiresOn());
    assertEquals(root.getExpiresOn(), reply.getExpiresOn());
    verify(postRepository, atLeastOnce()).save(eq(root));
    verify(postRepository, atLeastOnce()).save(eq(reply));
  }

  @Test
  void likeTransitionUsesOneAtomicCounterWriteAndOneBulkReplySynchronization() {
    var changedOn = Instant.parse("2026-07-29T03:00:00Z");
    var root = Post.builder()
        .id("root")
        .rootId("root")
        .createdOn(Instant.parse("2026-07-29T01:00:00Z"))
        .likesCount(0)
        .threadReplyLikesCount(0)
        .threadReplyCount(10_000)
        .build();
    var updated = Post.builder()
        .id("root")
        .rootId("root")
        .createdOn(root.getCreatedOn())
        .likesCount(1)
        .threadReplyLikesCount(0)
        .threadReplyCount(10_000)
        .build();
    var factory = DomainMongoOperationsTestFactory
        .create(mongo);
    var updatedEnvelope =
        DomainMongoOperationsTestFactory
            .envelope(mongo, updated);
    when(mongo.findAndModify(
        any(Query.class),
        any(UpdateDefinition.class),
        any(FindAndModifyOptions.class),
        eq(Document.class), eq("content"))).thenReturn(updatedEnvelope);
    var service = new PostExpirationService(
        postRepository,
        new MongoPostExpirationStore(factory),
        Clock.fixed(changedOn, ZoneOffset.UTC),
        true);

    service.applyLikeTransition(root, null, 1, changedOn);

    var counterUpdate = ArgumentCaptor.forClass(UpdateDefinition.class);
    verify(mongo).findAndModify(
        any(Query.class),
        counterUpdate.capture(),
        any(FindAndModifyOptions.class),
        eq(Document.class), eq("content"));
    assertEquals(1, counterUpdate.getValue().getUpdateObject()
        .get("$inc", Document.class).getInteger("payload.likesCount"));
    verify(mongo).updateMulti(
        any(Query.class), any(UpdateDefinition.class), eq(Document.class), eq("content"));
    verify(postRepository, never()).findByRootIdOrderByCreatedOnAsc(any());
  }

  @Test
  void replyCountClampIsOneAtomicWriteAcrossAConcurrentIncrement() {
    var changedOn = Instant.parse("2026-07-29T03:00:00Z");
    var root = Post.builder()
        .id("root")
        .rootId("root")
        .createdOn(Instant.parse("2026-07-29T01:00:00Z"))
        .threadReplyCount(0)
        .likesCount(0)
        .threadReplyLikesCount(0)
        .build();
    var reply = Post.builder()
        .id("reply")
        .rootId("root")
        .parentId("root")
        .build();
    var linearized = Post.builder()
        .id("root")
        .rootId("root")
        .createdOn(root.getCreatedOn())
        .threadReplyCount(1)
        .likesCount(0)
        .threadReplyLikesCount(0)
        .lastUpdatedOn(changedOn)
        .build();
    when(postRepository.findByRootIdOrderByCreatedOnAsc("root"))
        .thenReturn(List.of(root, reply));
    when(mongo.remove(any(Query.class), eq(Document.class), any(String.class)))
        .thenReturn(DeleteResult.acknowledged(1));
    var factory = DomainMongoOperationsTestFactory
        .create(mongo);
    var linearizedEnvelope =
        DomainMongoOperationsTestFactory
            .envelope(mongo, linearized);
    when(mongo.findAndModify(
        any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class),
        eq(Document.class), eq("content")))
        .thenAnswer(invocation -> {
          var update = invocation.<UpdateDefinition>getArgument(1).getUpdateObject();
          if (update.toString().contains("$max") && update.toString().contains("$subtract")) {
            return linearizedEnvelope;
          }
          return null;
        });
    var service = new PostExpirationService(
        postRepository,
        new MongoPostExpirationStore(factory),
        Clock.fixed(changedOn, ZoneOffset.UTC),
        true);

    service.deletePostTree(reply);

    verify(mongo, times(1)).findAndModify(
        any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class),
        eq(Document.class), eq("content"));
  }
}
