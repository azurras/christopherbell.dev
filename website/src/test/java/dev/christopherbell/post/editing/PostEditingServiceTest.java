package dev.christopherbell.post.editing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.post.PostMapper;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.expiration.PostExpirationService;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.post.model.PostDetail;
import dev.christopherbell.post.model.PostLinkPreview;
import dev.christopherbell.post.preview.PostLinkPreviewService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PostEditingServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-26T12:15:00Z");
  @Mock private PostRepository posts;
  @Mock private PostMapper mapper;
  @Mock private PostLinkPreviewService previews;
  @Mock private PostExpirationService expiration;
  private PostEditingService service;

  @BeforeEach
  void setUp() {
    service = new PostEditingService(
        posts,
        mapper,
        previews,
        expiration,
        new PostEditingProperties(Duration.ofMinutes(15), 10),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  @DisplayName("An author can edit at 14:59 without changing creation time")
  void edit_whenOwnerInsideWindow_savesAuditAndRefreshesPreviews() throws Exception {
    var created = NOW.minus(Duration.ofMinutes(14)).minusSeconds(59);
    var post = post(created);
    var preview = new PostLinkPreview("https://example.com", "Example", null, null, "example.com");
    when(posts.findById("post-1")).thenReturn(Optional.of(post));
    when(previews.resolveForText("after https://example.com")).thenReturn(List.of(preview));
    when(posts.save(any(Post.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(mapper.toDetail(any(Post.class))).thenAnswer(invocation -> {
      var saved = invocation.<Post>getArgument(0);
      return PostDetail.builder().id(saved.getId()).text(saved.getText()).editedOn(saved.getEditedOn()).build();
    });

    var result = service.edit(
        "post-1", new PostEditRequest("after https://example.com"), "author", false);

    var saved = ArgumentCaptor.forClass(Post.class);
    verify(posts).save(saved.capture());
    assertThat(saved.getValue().getCreatedOn()).isEqualTo(created);
    assertThat(saved.getValue().getEditedOn()).isEqualTo(NOW);
    assertThat(saved.getValue().getEditAudit()).singleElement().satisfies(event -> {
      assertThat(event.beforeText()).isEqualTo("before");
      assertThat(event.afterText()).isEqualTo("after https://example.com");
      assertThat(event.editorAccountId()).isEqualTo("author");
    });
    assertThat(saved.getValue().getLinkPreviews()).containsExactly(preview);
    assertThat(result.editedOn()).isEqualTo(NOW);
  }

  @Test
  @DisplayName("The exact 15 minute boundary is closed")
  void edit_atWindowBoundary_isRejected() {
    when(posts.findById("post-1")).thenReturn(Optional.of(post(NOW.minus(Duration.ofMinutes(15)))));

    assertThrows(InvalidRequestException.class, () -> service.edit(
        "post-1", new PostEditRequest("after"), "author", false));

    verify(posts, never()).save(any());
  }

  @Test
  @DisplayName("A non-owner sees the same not-found partition while an admin may edit")
  void edit_partitionsNonOwnerAndAdmin() throws Exception {
    var post = post(NOW.minusSeconds(1));
    when(posts.findById("post-1")).thenReturn(Optional.of(post));
    assertThrows(ResourceNotFoundException.class, () -> service.edit(
        "post-1", new PostEditRequest("after"), "stranger", false));

    when(posts.save(any(Post.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(mapper.toDetail(any(Post.class))).thenReturn(PostDetail.builder().id("post-1").build());
    service.edit("post-1", new PostEditRequest("after"), "admin", true);

    verify(posts).save(post);
  }

  @Test
  @DisplayName("Expired posts cannot be edited")
  void edit_whenExpired_isRejected() {
    var post = post(NOW.minusSeconds(1));
    when(posts.findById("post-1")).thenReturn(Optional.of(post));
    when(expiration.isExpired(post)).thenReturn(true);

    assertThrows(InvalidRequestException.class, () -> service.edit(
        "post-1", new PostEditRequest("after"), "author", false));
  }

  @Test
  @DisplayName("Revision history retains only the ten most recent bounded events")
  void edit_capsAuditAtTen() throws Exception {
    var post = post(NOW.minusSeconds(1));
    var old = new ArrayList<PostEditAuditEvent>();
    for (int index = 0; index < 10; index++) {
      old.add(new PostEditAuditEvent("author", "before-" + index, "after-" + index, NOW.minusSeconds(20 - index)));
    }
    post.setEditAudit(old);
    when(posts.findById("post-1")).thenReturn(Optional.of(post));
    when(posts.save(any(Post.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(mapper.toDetail(any(Post.class))).thenReturn(PostDetail.builder().id("post-1").build());

    service.edit("post-1", new PostEditRequest("newest"), "author", false);

    assertThat(post.getEditAudit()).hasSize(10);
    assertThat(post.getEditAudit().get(0).beforeText()).isEqualTo("before-1");
    assertThat(post.getEditAudit().get(9).afterText()).isEqualTo("newest");
  }

  private Post post(Instant createdOn) {
    return Post.builder()
        .id("post-1")
        .accountId("author")
        .text("before")
        .createdOn(createdOn)
        .expiresOn(NOW.plusSeconds(60))
        .build();
  }
}
