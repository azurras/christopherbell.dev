package dev.christopherbell.post.hide;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.api.model.Response;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API for hiding and unhiding post threads from personal feeds. */
@RequiredArgsConstructor
@RequestMapping("/api/posts/2026-06-02")
@RestController
public class HiddenPostThreadController {
  private final HiddenPostThreadService hiddenPostThreadService;

  @PutMapping(value = "/{postId}/hide-thread", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<Void>> hideThread(@PathVariable String postId)
      throws InvalidRequestException, ResourceNotFoundException {
    hiddenPostThreadService.hideThread(postId);
    return new ResponseEntity<>(Response.<Void>builder().success(true).build(), HttpStatus.OK);
  }

  @DeleteMapping(value = "/{rootPostId}/hide-thread", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<Void>> unhideThread(@PathVariable String rootPostId)
      throws InvalidRequestException {
    hiddenPostThreadService.unhideThread(rootPostId);
    return new ResponseEntity<>(Response.<Void>builder().success(true).build(), HttpStatus.OK);
  }
}
