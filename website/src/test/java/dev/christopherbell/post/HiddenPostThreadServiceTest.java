package dev.christopherbell.post;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.post.hide.HiddenPostThread;
import dev.christopherbell.post.hide.HiddenPostThreadRepository;
import dev.christopherbell.post.hide.HiddenPostThreadService;
import dev.christopherbell.post.model.Post;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class HiddenPostThreadServiceTest {
  @Mock private HiddenPostThreadRepository hiddenPostThreadRepository;
  @Mock private PostRepository postRepository;
  @Mock private PermissionService permissionService;

  @Test
  public void hideThread_savesRootThreadForReply() throws Exception {
    var reply = Post.builder().id("reply").rootId("root").parentId("root").build();
    var service = service();

    when(postRepository.findById("reply")).thenReturn(Optional.of(reply));
    when(permissionService.getSelfId()).thenReturn("self");
    when(hiddenPostThreadRepository.findByAccountIdAndRootPostId("self", "root"))
        .thenReturn(Optional.empty());
    when(hiddenPostThreadRepository.save(any(HiddenPostThread.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.hideThread("reply");

    verify(hiddenPostThreadRepository).save(any(HiddenPostThread.class));
  }

  @Test
  public void hideThread_reusesExistingHiddenThread() throws Exception {
    var root = Post.builder().id("root").build();
    var existing = HiddenPostThread.builder().accountId("self").rootPostId("root").build();
    var service = service();

    when(postRepository.findById("root")).thenReturn(Optional.of(root));
    when(permissionService.getSelfId()).thenReturn("self");
    when(hiddenPostThreadRepository.findByAccountIdAndRootPostId("self", "root"))
        .thenReturn(Optional.of(existing));

    service.hideThread("root");

    verify(hiddenPostThreadRepository, never()).save(any(HiddenPostThread.class));
  }

  @Test
  public void hideThread_rejectsBlankPostId() {
    assertThrows(InvalidRequestException.class, () -> service().hideThread(" "));
  }

  @Test
  public void hiddenRootIdsForSelf_returnsHiddenRootIds() {
    var service = service();
    when(permissionService.getSelfId()).thenReturn("self");
    when(hiddenPostThreadRepository.findByAccountId("self"))
        .thenReturn(List.of(
            HiddenPostThread.builder().rootPostId("root-one").build(),
            HiddenPostThread.builder().rootPostId("root-two").build()));

    assertEquals(Set.of("root-one", "root-two"), service.hiddenRootIdsForSelf());
  }

  private HiddenPostThreadService service() {
    return new HiddenPostThreadService(hiddenPostThreadRepository, postRepository, permissionService);
  }
}
