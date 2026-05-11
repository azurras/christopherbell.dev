package dev.christopherbell.admin;

import static dev.christopherbell.libs.api.APIVersion.V20260509;

import dev.christopherbell.admin.model.AdminActivity;
import dev.christopherbell.libs.api.model.Response;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RequestMapping("/api/admin/activity")
@RestController
public class AdminActivityController {
  private final AdminActivityService adminActivityService;

  @GetMapping(value = V20260509, produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<List<AdminActivity>>> getRecentActivity() {
    return new ResponseEntity<>(
        Response.<List<AdminActivity>>builder()
            .payload(adminActivityService.getRecentActivity())
            .success(true)
            .build(),
        HttpStatus.OK);
  }
}
