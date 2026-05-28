package dev.christopherbell.post;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.AccountServiceStub;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.notification.delivery.NotificationDeliveryService;
import dev.christopherbell.post.creation.PostCreationService;
import dev.christopherbell.post.expiration.PostExpirationService;
import dev.christopherbell.post.feed.PostFeedService;
import dev.christopherbell.post.interaction.PostInteractionService;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.post.model.PostCreateRequest;
import dev.christopherbell.post.model.PostDetail;
import dev.christopherbell.post.preview.PostLinkPreviewService;
import dev.christopherbell.post.thread.PostThreadService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class PostServiceTest {
  @Mock private PostRepository postRepository;
  @Mock private AccountRepository accountRepository;
  @Mock private PostMapper postMapper;
  @Mock private dev.christopherbell.permission.PermissionService permissionService;
  @Mock private NotificationDeliveryService notificationDeliveryService;
  @Mock private PostLinkPreviewService postLinkPreviewService;
  private PostService postService;
  private PostExpirationService postExpirationService;

  @BeforeEach
  void setUp() {
    postExpirationService = new PostExpirationService(postRepository, true);
    postService = new PostService(
        permissionService,
        new PostCreationService(
            postRepository,
            accountRepository,
            postMapper,
            notificationDeliveryService,
            postLinkPreviewService,
            postExpirationService),
        new PostFeedService(postRepository, accountRepository, postMapper, postExpirationService),
        new PostThreadService(postRepository, accountRepository, postExpirationService),
        new PostInteractionService(
            postRepository,
            accountRepository,
            postMapper,
            notificationDeliveryService,
            postExpirationService));
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
    verify(notificationDeliveryService).createMentionNotifications(eq(post), eq(existing));
    verify(postMapper).toDetail(eq(post));
    verifyNoMoreInteractions(accountRepository, postRepository, postMapper, notificationDeliveryService);
  }

  @Test
  @DisplayName("Create assigns the 24 hour base expiration when expiration is enabled")
  public void testCreatePost_whenExpirationEnabled_AssignsBaseLifespan() throws Exception {
    var existing = AccountServiceStub.getAccountWhenExistsStub();
    var service = spy(postService);
    doReturn(existing.getId()).when(service).getSelfId();
    when(accountRepository.findById(eq(existing.getId()))).thenReturn(Optional.of(existing));
    when(postRepository.save(any(Post.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(postMapper.toDetail(any(Post.class))).thenReturn(PostDetail.builder().id("p1").build());

    service.createPost(PostCreateRequest.builder().text("still here").build());

    var savedPost = ArgumentCaptor.forClass(Post.class);
    verify(postRepository).save(savedPost.capture());
    assertEquals(
        savedPost.getValue().getCreatedOn().plus(Duration.ofHours(24)),
        savedPost.getValue().getExpiresOn());
  }

  @Test
  @DisplayName("Create stores previews resolved from post links")
  public void testCreatePost_whenTextHasLinks_StoresResolvedPreviews() throws Exception {
    var existing = AccountServiceStub.getAccountWhenExistsStub();
    var service = spy(postService);
    doReturn(existing.getId()).when(service).getSelfId();
    when(accountRepository.findById(eq(existing.getId()))).thenReturn(Optional.of(existing));
    when(postRepository.save(any(Post.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(postMapper.toDetail(any(Post.class))).thenReturn(PostDetail.builder().id("p1").build());
    var preview = dev.christopherbell.post.model.PostLinkPreview.builder()
        .url("https://example.com/lunch")
        .domain("example.com")
        .title("Lunch")
        .build();
    when(postLinkPreviewService.resolveForText(eq("Go https://example.com/lunch")))
        .thenReturn(List.of(preview));

    service.createPost(PostCreateRequest.builder().text("Go https://example.com/lunch").build());

    var savedPost = ArgumentCaptor.forClass(Post.class);
    verify(postRepository).save(savedPost.capture());
    assertEquals(List.of(preview), savedPost.getValue().getLinkPreviews());
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
  @DisplayName("Create: suspended account -> 400 InvalidRequestException")
  public void testCreatePost_whenAccountSuspended_Throws400() {
    var suspended = Account.builder()
        .id("suspended")
        .username("silent")
        .status(AccountStatus.SUSPENDED)
        .build();
    var service = spy(postService);
    doReturn(suspended.getId()).when(service).getSelfId();
    when(accountRepository.findById(eq(suspended.getId()))).thenReturn(Optional.of(suspended));

    assertThrows(InvalidRequestException.class, () -> service.createPost(PostCreateRequest.builder()
        .text("hello")
        .build()));

    verify(postRepository, never()).save(any(Post.class));
    verify(notificationDeliveryService, never()).createMentionNotifications(any(Post.class), any(Account.class));
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
        .rootId("root")
        .parentId("root")
        .accountId("other")
        .text("old")
        .createdOn(Instant.now().minus(Duration.ofHours(48)))
        .lastUpdatedOn(Instant.now().minus(Duration.ofHours(48)))
        .expiresOn(Instant.now().minus(Duration.ofHours(1)))
        .build();
    var child = Post.builder()
        .id("child")
        .rootId("root")
        .parentId("parent")
        .accountId("other")
        .text("older reply")
        .createdOn(Instant.now().minus(Duration.ofHours(47)))
        .lastUpdatedOn(Instant.now().minus(Duration.ofHours(47)))
        .expiresOn(Instant.now().plus(Duration.ofHours(1)))
        .build();
    when(postRepository.findById(eq("parent"))).thenReturn(Optional.of(expiredParent));
    when(postRepository.findByRootIdOrderByCreatedOnAsc(eq("root")))
        .thenReturn(List.of(expiredParent, child));

    var request = PostCreateRequest.builder().text("child").parentId("parent").build();

    assertThrows(ResourceNotFoundException.class, () -> service.createPost(request));
    verify(postRepository).deleteAll(eq(List.of(expiredParent, child)));
  }

  @Test
  @DisplayName("Create reply extends the thread root expiration")
  public void testCreatePost_whenReply_ExtendsThreadRootExpiration() throws Exception {
    var existing = AccountServiceStub.getAccountWhenExistsStub();
    var service = spy(postService);
    doReturn(existing.getId()).when(service).getSelfId();
    when(accountRepository.findById(eq(existing.getId()))).thenReturn(Optional.of(existing));

    var rootCreated = Instant.now().minus(Duration.ofHours(2));
    var rootExpiration = rootCreated.plus(Duration.ofHours(24));
    var root = Post.builder()
        .id("root")
        .rootId("root")
        .accountId("other")
        .text("root")
        .createdOn(rootCreated)
        .lastUpdatedOn(rootCreated)
        .expiresOn(rootExpiration)
        .build();
    var savedPosts = new ArrayList<Post>();
    when(postRepository.findById(eq("root"))).thenReturn(Optional.of(root));
    when(postRepository.save(any(Post.class))).thenAnswer(invocation -> {
      Post saved = invocation.getArgument(0);
      savedPosts.add(saved);
      return saved;
    });
    when(postRepository.findByRootIdOrderByCreatedOnAsc(eq("root")))
        .thenAnswer(invocation -> savedPosts.stream()
            .filter(this::isReply)
            .collect(() -> new ArrayList<>(List.of(root)), ArrayList::add, ArrayList::addAll));
    when(postMapper.toDetail(any(Post.class))).thenReturn(PostDetail.builder().id("reply").build());

    service.createPost(PostCreateRequest.builder().text("reply").parentId("root").build());

    var reply = savedPosts.stream().filter(this::isReply).findFirst().orElseThrow();
    var expectedExpiration = rootCreated.plus(Duration.ofHours(48));
    assertEquals(expectedExpiration, root.getExpiresOn());
    assertEquals(expectedExpiration, reply.getExpiresOn());
  }

  @Test
  @DisplayName("Create reply notifies the parent post owner")
  public void testCreatePost_whenReply_NotifiesParentOwner() throws Exception {
    var replier = Account.builder().id("replier").username("replier").build();
    var parentAuthor = Account.builder().id("author").username("author").build();
    var service = spy(postService);
    doReturn(replier.getId()).when(service).getSelfId();
    when(accountRepository.findById(eq(replier.getId()))).thenReturn(Optional.of(replier));
    when(accountRepository.findById(eq(parentAuthor.getId()))).thenReturn(Optional.of(parentAuthor));

    var parent = Post.builder()
        .id("parent")
        .rootId("parent")
        .accountId(parentAuthor.getId())
        .text("parent text")
        .createdOn(Instant.now().minus(Duration.ofMinutes(10)))
        .lastUpdatedOn(Instant.now().minus(Duration.ofMinutes(10)))
        .expiresOn(Instant.now().plus(Duration.ofHours(24)))
        .build();
    when(postRepository.findById(eq("parent"))).thenReturn(Optional.of(parent));
    when(postRepository.save(any(Post.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(postRepository.findByRootIdOrderByCreatedOnAsc(eq("parent"))).thenReturn(List.of(parent));
    when(postMapper.toDetail(any(Post.class))).thenReturn(PostDetail.builder().id("reply").build());

    service.createPost(PostCreateRequest.builder().text("reply text").parentId("parent").build());

    var replyCaptor = ArgumentCaptor.forClass(Post.class);
    verify(notificationDeliveryService)
        .createPostCommentNotification(replyCaptor.capture(), eq(replier), eq(parentAuthor));
    assertEquals("reply text", replyCaptor.getValue().getText());
    assertEquals("parent", replyCaptor.getValue().getParentId());
  }

  @Test
  @DisplayName("Create nested reply extends the thread root expiration")
  public void testCreatePost_whenNestedReply_ExtendsThreadRootExpiration() throws Exception {
    var existing = AccountServiceStub.getAccountWhenExistsStub();
    var service = spy(postService);
    doReturn(existing.getId()).when(service).getSelfId();
    when(accountRepository.findById(eq(existing.getId()))).thenReturn(Optional.of(existing));

    var rootCreated = Instant.now().minus(Duration.ofHours(3));
    var root = Post.builder()
        .id("root")
        .rootId("root")
        .accountId("other")
        .text("root")
        .createdOn(rootCreated)
        .lastUpdatedOn(rootCreated)
        .expiresOn(rootCreated.plus(Duration.ofHours(48)))
        .build();
    var child = Post.builder()
        .id("child")
        .rootId("root")
        .parentId("root")
        .accountId("other")
        .text("child")
        .createdOn(rootCreated.plus(Duration.ofMinutes(10)))
        .lastUpdatedOn(rootCreated.plus(Duration.ofMinutes(10)))
        .expiresOn(rootCreated.plus(Duration.ofHours(48)))
        .build();
    var savedPosts = new ArrayList<Post>();
    when(postRepository.findById(eq("child"))).thenReturn(Optional.of(child));
    when(postRepository.findById(eq("root"))).thenReturn(Optional.of(root));
    when(postRepository.save(any(Post.class))).thenAnswer(invocation -> {
      Post saved = invocation.getArgument(0);
      savedPosts.add(saved);
      return saved;
    });
    when(postRepository.findByRootIdOrderByCreatedOnAsc(eq("root")))
        .thenAnswer(invocation -> savedPosts.stream()
            .filter(this::isReply)
            .collect(() -> new ArrayList<>(List.of(root, child)), ArrayList::add, ArrayList::addAll));
    when(postMapper.toDetail(any(Post.class))).thenReturn(PostDetail.builder().id("grandchild").build());

    service.createPost(PostCreateRequest.builder().text("grandchild").parentId("child").build());

    var grandchild = savedPosts.stream()
        .filter(post -> "child".equals(post.getParentId()))
        .findFirst()
        .orElseThrow();
    var expectedExpiration = rootCreated.plus(Duration.ofHours(72));
    assertEquals(expectedExpiration, root.getExpiresOn());
    assertEquals(expectedExpiration, child.getExpiresOn());
    assertEquals(expectedExpiration, grandchild.getExpiresOn());
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
    verify(postRepository, atLeastOnce()).findByRootIdOrderByCreatedOnAsc(eq("p1"));
    verify(postRepository).save(eq(p1));
    verify(postMapper).toDetail(eq(p1));
    verifyNoMoreInteractions(accountRepository, postRepository, postMapper);
  }

  @Test
  @DisplayName("Toggle like adjusts expiration window")
  public void testToggleLike_updatesExpirationWithLikes() throws Exception {
    var author = Account.builder().id("author").username("author").build();
    var liker = Account.builder().id("liker").username("liker").build();
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
    when(accountRepository.findById(eq(liker.getId()))).thenReturn(Optional.of(liker));
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
    verify(notificationDeliveryService).createPostLikeNotification(eq(post), eq(liker), eq(author));
  }

  @Test
  @DisplayName("Toggle like on a reply extends the thread root lifespan")
  public void testToggleLike_whenReplyLiked_ExtendsThreadRootExpiration() throws Exception {
    var author = Account.builder().id("author").username("author").build();
    var service = spy(postService);
    doReturn("liker").when(service).getSelfId();
    var rootCreated = Instant.now().minus(Duration.ofHours(2));
    var replyCreated = Instant.now().minus(Duration.ofHours(1));
    var root = Post.builder()
        .id("root")
        .rootId("root")
        .accountId(author.getId())
        .createdOn(rootCreated)
        .lastUpdatedOn(rootCreated)
        .likedBy(new HashSet<>())
        .likesCount(0)
        .expiresOn(rootCreated.plus(Duration.ofHours(24)))
        .build();
    var reply = Post.builder()
        .id("reply")
        .rootId("root")
        .parentId("root")
        .accountId(author.getId())
        .createdOn(replyCreated)
        .lastUpdatedOn(replyCreated)
        .likedBy(new HashSet<>())
        .likesCount(0)
        .expiresOn(replyCreated.plus(Duration.ofHours(24)))
        .build();
    when(postRepository.findById(eq("reply"))).thenReturn(Optional.of(reply));
    when(postRepository.findById(eq("root"))).thenReturn(Optional.of(root));
    when(postRepository.save(any(Post.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(accountRepository.findById(eq(author.getId()))).thenReturn(Optional.of(author));
    when(postRepository.countByParentId(eq("reply"))).thenReturn(0L);
    when(postRepository.findByRootIdOrderByCreatedOnAsc(eq("root"))).thenReturn(List.of(root, reply));

    service.toggleLike("reply");

    assertEquals(rootCreated.plus(Duration.ofHours(72)), root.getExpiresOn());
    assertEquals(rootCreated.plus(Duration.ofHours(72)), reply.getExpiresOn());
    verify(postRepository, atLeastOnce()).save(eq(root));
  }

  @Test
  @DisplayName("Toggle like on root synchronizes deep reply expiration")
  public void testToggleLike_whenRootLiked_SynchronizesNestedReplyExpiration() throws Exception {
    var author = Account.builder().id("author").username("author").build();
    var service = spy(postService);
    doReturn("liker").when(service).getSelfId();
    var rootCreated = Instant.now().minus(Duration.ofHours(2));
    var originalExpiration = rootCreated.plus(Duration.ofHours(24));
    var root = Post.builder()
        .id("root")
        .rootId("root")
        .accountId(author.getId())
        .createdOn(rootCreated)
        .lastUpdatedOn(rootCreated)
        .likedBy(new HashSet<>())
        .likesCount(0)
        .expiresOn(originalExpiration)
        .build();
    var child = Post.builder()
        .id("child")
        .rootId("root")
        .parentId("root")
        .accountId(author.getId())
        .createdOn(rootCreated.plus(Duration.ofMinutes(10)))
        .lastUpdatedOn(rootCreated.plus(Duration.ofMinutes(10)))
        .expiresOn(originalExpiration)
        .build();
    var grandchild = Post.builder()
        .id("grandchild")
        .rootId("root")
        .parentId("child")
        .accountId(author.getId())
        .createdOn(rootCreated.plus(Duration.ofMinutes(20)))
        .lastUpdatedOn(rootCreated.plus(Duration.ofMinutes(20)))
        .expiresOn(originalExpiration)
        .build();
    when(postRepository.findById(eq("root"))).thenReturn(Optional.of(root));
    when(postRepository.save(any(Post.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(accountRepository.findById(eq(author.getId()))).thenReturn(Optional.of(author));
    when(postRepository.countByParentId(eq("root"))).thenReturn(1L);
    when(postRepository.findByRootIdOrderByCreatedOnAsc(eq("root"))).thenReturn(List.of(root, child, grandchild));

    service.toggleLike("root");

    var expectedExpiration = rootCreated.plus(Duration.ofHours(96));
    assertEquals(expectedExpiration, root.getExpiresOn());
    assertEquals(expectedExpiration, child.getExpiresOn());
    assertEquals(expectedExpiration, grandchild.getExpiresOn());
    verify(postRepository, atLeastOnce()).save(eq(child));
    verify(postRepository, atLeastOnce()).save(eq(grandchild));
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

    postExpirationService.purgeExpiredPosts();

    verify(postRepository).findByExpiresOnIsNull();
    verify(postRepository).save(eq(stale));
    verify(postRepository).findByExpiresOnLessThanEqual(org.mockito.ArgumentMatchers.any(Instant.class));
    assertNotNull(stale.getExpiresOn());
  }

  @Test
  @DisplayName("Cleanup job removes descendants when a parent expires")
  public void testPurgeExpiredPosts_deletesExpiredPostTree() {
    var parent = Post.builder()
        .id("parent")
        .rootId("root")
        .parentId("root")
        .expiresOn(Instant.now().minus(Duration.ofMinutes(1)))
        .build();
    var child = Post.builder()
        .id("child")
        .rootId("root")
        .parentId("parent")
        .expiresOn(Instant.now().plus(Duration.ofHours(1)))
        .build();
    when(postRepository.findByExpiresOnLessThanEqual(any(Instant.class))).thenReturn(List.of(parent));
    when(postRepository.findByRootIdOrderByCreatedOnAsc(eq("root"))).thenReturn(List.of(parent, child));

    postExpirationService.purgeExpiredPosts();

    verify(postRepository).deleteAll(eq(List.of(parent, child)));
  }

  @Test
  @DisplayName("GlobalFeed: returns newest posts with usernames")
  public void testGetGlobalFeed_returnsMappedItems() {
    var preview = dev.christopherbell.post.model.PostLinkPreview.builder()
        .url("https://example.com")
        .domain("example.com")
        .title("Example")
        .build();
    var p1 = Post.builder()
        .id("p1")
        .accountId("a1")
        .text("t1")
        .createdOn(Instant.now())
        .linkPreviews(List.of(preview))
        .build();
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
    assertEquals(List.of(preview), result.get(0).linkPreviews());
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

  private boolean isReply(Post post) {
    return post != null && post.getParentId() != null && !post.getParentId().isBlank();
  }
}
