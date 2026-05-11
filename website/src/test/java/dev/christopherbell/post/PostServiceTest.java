package dev.christopherbell.post;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.AccountServiceStub;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.notification.NotificationService;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.post.model.PostCreateRequest;
import dev.christopherbell.post.model.PostDetail;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class PostServiceTest {
  @Mock private PostRepository postRepository;
  @Mock private AccountRepository accountRepository;
  @Mock private PostMapper postMapper;
  @Mock private dev.christopherbell.permission.PermissionService permissionService;
  @Mock private NotificationService notificationService;
  private PostService postService;

  @BeforeEach
  void setUp() {
    postService = new PostService(
        postRepository,
        accountRepository,
        postMapper,
        permissionService,
        notificationService,
        true);
  }

  @Test
  @DisplayName("Create: valid -> saves and returns detail")
  public void testCreatePost_whenValid_SavesAndReturnsDetail() throws Exception {
    var existing = AccountServiceStub.getAccountWhenExistsStub();
    var request = PostCreateRequest.builder().text("hello world").build();

    var service = spy(postService);
    doReturn(existing.getId()).when(service).getSelfId();
    when(accountRepository.findById(eq(existing.getId()))).thenReturn(Optional.of(existing));

    var post = Post.builder()
        .id("p1").accountId(existing.getId()).text("hello world")
        .createdOn(Instant.now()).lastUpdatedOn(Instant.now())
        .build();
    when(postRepository.save(org.mockito.ArgumentMatchers.any(Post.class))).thenReturn(post);
    var detail = PostDetail.builder().id("p1").accountId(existing.getId()).text("hello world").build();
    when(postMapper.toDetail(eq(post))).thenReturn(detail);

    var result = service.createPost(request);

    assertNotNull(result);
    assertEquals("p1", result.id());
    verify(accountRepository).findById(eq(existing.getId()));
    verify(postRepository).save(org.mockito.ArgumentMatchers.any(Post.class));
    verify(notificationService).createMentionNotifications(eq(post), eq(existing));
    verify(postMapper).toDetail(eq(post));
    verifyNoMoreInteractions(accountRepository, postRepository, postMapper, notificationService);
  }

  @Test
  @DisplayName("Create: blank -> 400 InvalidRequestException")
  public void testCreatePost_whenBlank_Throws400() {
    var request = PostCreateRequest.builder().text("   ").build();
    assertThrows(InvalidRequestException.class, () -> postService.createPost(request));
  }

  @Test
  @DisplayName("Create: too long -> 400 InvalidRequestException")
  public void testCreatePost_whenTooLong_Throws400() {
    var longText = "a".repeat(281);
    var request = PostCreateRequest.builder().text(longText).build();
    assertThrows(InvalidRequestException.class, () -> postService.createPost(request));
  }

  @Test
  @DisplayName("Create: account not found -> 404")
  public void testCreatePost_whenAccountMissing_Throws404() {
    var request = PostCreateRequest.builder().text("hello").build();
    var service = spy(postService);
    doReturn("missing").when(service).getSelfId();
    when(accountRepository.findById(eq("missing"))).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> service.createPost(request));

    verify(accountRepository).findById(eq("missing"));
    verifyNoMoreInteractions(accountRepository);
  }

  @Test
  @DisplayName("Create reply: parent expired -> 404 and cleanup")
  public void testCreatePost_whenParentExpired_Throws404() throws Exception {
    var existing = AccountServiceStub.getAccountWhenExistsStub();
    var service = spy(postService);
    doReturn(existing.getId()).when(service).getSelfId();
    when(accountRepository.findById(eq(existing.getId()))).thenReturn(Optional.of(existing));

    var expiredParent = Post.builder()
        .id("parent")
        .accountId("other")
        .text("old")
        .createdOn(Instant.now().minus(Duration.ofHours(48)))
        .lastUpdatedOn(Instant.now().minus(Duration.ofHours(48)))
        .expiresOn(Instant.now().minus(Duration.ofHours(1)))
        .build();
    when(postRepository.findById(eq("parent"))).thenReturn(Optional.of(expiredParent));

    var request = PostCreateRequest.builder().text("child").parentId("parent").build();

    assertThrows(ResourceNotFoundException.class, () -> service.createPost(request));
    verify(postRepository).delete(eq(expiredParent));
  }

  @Test
  @DisplayName("GetMy: returns mapped list")
  public void testGetMyPosts_whenSome_ReturnsList() throws Exception {
    var existing = AccountServiceStub.getAccountWhenExistsStub();
    var service = spy(postService);
    doReturn(existing.getId()).when(service).getSelfId();
    when(accountRepository.findById(eq(existing.getId()))).thenReturn(Optional.of(existing));

    var p1 = Post.builder().id("p1").accountId(existing.getId()).text("a").build();
    var d1 = PostDetail.builder().id("p1").text("a").build();
    when(postRepository.findByAccountIdOrderByCreatedOnDesc(eq(existing.getId())))
        .thenReturn(List.of(p1));
    when(postMapper.toDetail(eq(p1))).thenReturn(d1);

    var list = service.getMyPosts();
    assertEquals(1, list.size());
    assertEquals("p1", list.get(0).id());
  }

  @Test
  @DisplayName("GetMy: account not found -> 404")
  public void testGetMyPosts_whenAccountMissing_Throws404() {
    var service = spy(postService);
    doReturn("missing").when(service).getSelfId();
    when(accountRepository.findById(eq("missing"))).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, service::getMyPosts);
  }

  @Test
  @DisplayName("GetByAccount: invalid id -> 400")
  public void testGetPostsByAccount_whenInvalid_Throws400() {
    assertThrows(InvalidRequestException.class, () -> postService.getPostsByAccountId(null));
    assertThrows(InvalidRequestException.class, () -> postService.getPostsByAccountId("  "));
  }

  @Test
  @DisplayName("GetByAccount: not found -> 404")
  public void testGetPostsByAccount_whenMissing_Throws404() {
    when(accountRepository.findById(eq("missing"))).thenReturn(Optional.empty());
    assertThrows(ResourceNotFoundException.class, () -> postService.getPostsByAccountId("missing"));
  }

  @Test
  @DisplayName("GetByAccount: returns mapped list")
  public void testGetPostsByAccount_whenSome_ReturnsList() throws Exception {
    var existing = AccountServiceStub.getAccountWhenExistsStub();
    var p1 = Post.builder().id("p1").accountId(existing.getId()).text("t").build();
    var d1 = PostDetail.builder().id("p1").text("t").build();

    when(accountRepository.findById(eq(existing.getId()))).thenReturn(Optional.of(existing));
    when(postRepository.findByAccountIdOrderByCreatedOnDesc(eq(existing.getId())))
        .thenReturn(List.of(p1));
    when(postMapper.toDetail(eq(p1))).thenReturn(d1);

    var list = postService.getPostsByAccountId(existing.getId());
    assertEquals(1, list.size());
    assertEquals("p1", list.get(0).id());

    verify(accountRepository).findById(eq(existing.getId()));
    verify(postRepository).findByAccountIdOrderByCreatedOnDesc(eq(existing.getId()));
    verify(postRepository).save(eq(p1));
    verify(postMapper).toDetail(eq(p1));
    verifyNoMoreInteractions(accountRepository, postRepository, postMapper);
  }

  @Test
  @DisplayName("Toggle like adjusts expiration window")
  public void testToggleLike_updatesExpirationWithLikes() throws Exception {
    var author = Account.builder().id("author").username("author").build();
    var likerId = "liker";
    var service = spy(postService);
    doReturn(likerId).when(service).getSelfId();

    var created = Instant.now().minus(Duration.ofHours(1));
    var post = Post.builder()
        .id("p1")
        .accountId(author.getId())
        .text("hello")
        .createdOn(created)
        .lastUpdatedOn(created)
        .likesCount(0)
        .likedBy(new HashSet<>())
        .expiresOn(created.plus(Duration.ofHours(24)))
        .build();

    when(postRepository.findById(eq("p1"))).thenReturn(Optional.of(post));
    when(accountRepository.findById(eq(author.getId()))).thenReturn(Optional.of(author));
    when(postRepository.save(org.mockito.ArgumentMatchers.any(Post.class))).thenReturn(post);
    when(postRepository.countByParentId(eq("p1"))).thenReturn(0L);

    var likedItem = service.toggleLike("p1");
    assertEquals(1, post.getLikesCount());
    assertEquals(created.plus(Duration.ofHours(48)), post.getExpiresOn());
    assertNotNull(likedItem);
    assertEquals(true, likedItem.liked());

    var unlikedItem = service.toggleLike("p1");
    assertEquals(0, post.getLikesCount());
    assertEquals(created.plus(Duration.ofHours(24)), post.getExpiresOn());
    assertNotNull(unlikedItem);
    assertEquals(false, unlikedItem.liked());
  }

  @Test
  @DisplayName("Cleanup job assigns expirations before purging")
  public void testPurgeExpiredPosts_backfillsMissingExpiration() {
    var stale = Post.builder()
        .id("p1")
        .createdOn(Instant.now().minus(Duration.ofHours(2)))
        .likesCount(1)
        .build();

    when(postRepository.findByExpiresOnIsNull()).thenReturn(List.of(stale));

    postService.purgeExpiredPosts();

    verify(postRepository).findByExpiresOnIsNull();
    verify(postRepository).save(eq(stale));
    verify(postRepository).deleteByExpiresOnLessThanEqual(org.mockito.ArgumentMatchers.any(Instant.class));
    assertNotNull(stale.getExpiresOn());
  }

  @Test
  @DisplayName("GlobalFeed: returns newest posts with usernames")
  public void testGetGlobalFeed_returnsMappedItems() {
    var p1 = Post.builder().id("p1").accountId("a1").text("t1").createdOn(Instant.now()).build();
    var p2 = Post.builder().id("p2").accountId("a2").text("t2").createdOn(Instant.now()).build();
    Page<Post> page = new PageImpl<>(List.of(p1, p2), PageRequest.of(0, 20), 2);

    when(postRepository.findAll(org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
        .thenReturn(page);
    when(accountRepository.findAllById(eq(List.of("a1", "a2"))))
        .thenReturn(List.of(
            Account.builder().id("a1").username("user1").build(),
            Account.builder().id("a2").username("user2").build()
        ));

    var result = postService.getGlobalFeed(null, 20);
    assertEquals(2, result.size());
    assertEquals("p1", result.get(0).id());
    assertEquals("user1", result.get(0).username());
    assertEquals("p2", result.get(1).id());
    assertEquals("user2", result.get(1).username());
  }

  @Test
  @DisplayName("FollowingFeed: returns posts from followed accounts")
  public void testGetFollowingFeed_returnsFollowedPosts() throws Exception {
    var self = Account.builder()
        .id("self")
        .username("self_user")
        .followingIds(new HashSet<>(List.of("a1")))
        .build();
    var author = Account.builder().id("a1").username("followed").build();
    var post = Post.builder()
        .id("p1")
        .accountId("a1")
        .text("hello")
        .createdOn(Instant.now())
        .likedBy(new HashSet<>())
        .likesCount(0)
        .build();
    var service = spy(postService);
    doReturn("self").when(service).getSelfId();

    when(accountRepository.findById(eq("self"))).thenReturn(Optional.of(self));
    when(postRepository.findByAccountIdInOrderByCreatedOnDesc(
        eq(List.of("a1")),
        org.mockito.ArgumentMatchers.any(PageRequest.class)))
        .thenReturn(List.of(post));
    when(accountRepository.findAllById(eq(List.of("a1")))).thenReturn(List.of(author));
    when(postRepository.countByParentId(eq("p1"))).thenReturn(0L);

    var result = service.getFollowingFeed(null, 20);

    assertEquals(1, result.size());
    assertEquals("p1", result.get(0).id());
    assertEquals("followed", result.get(0).username());
  }
}
