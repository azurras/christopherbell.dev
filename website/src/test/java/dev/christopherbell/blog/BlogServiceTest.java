package dev.christopherbell.blog;

import static org.mockito.Mockito.when;

import dev.christopherbell.blog.model.BlogProperties;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class BlogServiceTest {

  private BlogService blogService;
  @Mock
  private BlogProperties blogProperties;

  @BeforeEach
  public void init() {
    blogService = new BlogService(blogProperties);
  }

  @Test
  public void testGetPostById_success() throws InvalidRequestException, ResourceNotFoundException {

    when(blogProperties.getPosts()).thenReturn(BlogStub.getPostsStub());

    var post = blogService.getPostById(BlogStub.BLOG_ID);
    Assertions.assertEquals(post.getPosts().getFirst().getId(),
        blogProperties.getPosts().getFirst().getId());
  }

  @Test
  public void testGetPostById_failure_nullId() {
    Assertions.assertThrows(InvalidRequestException.class, () -> {
      blogService.getPostById(null);
    });
  }

  @Test
  public void testGetPostById_failure_blankId() {
    Assertions.assertThrows(InvalidRequestException.class, () -> {
      blogService.getPostById("");
    });
  }

  @Test
  public void testGetPostById_failure_doesNotExist() {
    Assertions.assertThrows(InvalidRequestException.class, () -> {
      blogService.getPostById("-1");
    });
  }

  @Test
  public void testGetPostById_failure_validButAbsentIdIsNotFound() {
    when(blogProperties.getPosts()).thenReturn(BlogStub.getPostsStub());

    Assertions.assertThrows(ResourceNotFoundException.class, () -> {
      blogService.getPostById("00000000-0000-0000-0000-000000000000");
    });
  }

  @Test
  public void testGetPosts_success() {

    when(blogProperties.getPosts()).thenReturn(BlogStub.getPostsStub());

    var posts = blogService.getPosts();

    Assertions.assertEquals(posts.getPosts().size(),
        blogProperties.getPosts().size());
  }

}
