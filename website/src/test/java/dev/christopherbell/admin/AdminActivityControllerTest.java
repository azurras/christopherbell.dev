package dev.christopherbell.admin;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.christopherbell.admin.activity.AdminActivityController;
import dev.christopherbell.admin.activity.AdminActivityService;
import dev.christopherbell.admin.model.AdminActivity;
import dev.christopherbell.configuration.security.ControllerSliceSecurityTestConfig;
import dev.christopherbell.libs.api.APIVersion;
import dev.christopherbell.libs.api.controller.ControllerExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminActivityController.class)
@Import({ControllerExceptionHandler.class, ControllerSliceSecurityTestConfig.class})
class AdminActivityControllerTest {
  @Autowired private MockMvc mockMvc;
  @MockitoBean private AdminActivityService adminActivityService;

  @Test
  @DisplayName("Recent activity: admin -> 200 with activity list")
  @WithMockUser(authorities = {"ADMIN"})
  void getRecentActivity_whenAdmin_returnsActivityList() throws Exception {
    when(adminActivityService.getRecentActivity()).thenReturn(List.of(AdminActivity.builder()
        .id("activity-1")
        .actorAccountId("account-1")
        .actorUsername("cbell")
        .action("IMPORT_RESTAURANTS")
        .message("cbell started a restaurant import.")
        .createdOn(Instant.parse("2026-05-18T15:00:00Z"))
        .build()));

    mockMvc.perform(get("/api/admin/activity" + APIVersion.V20260509))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload[0].id").value("activity-1"))
        .andExpect(jsonPath("$.payload[0].actorUsername").value("cbell"))
        .andExpect(jsonPath("$.payload[0].action").value("IMPORT_RESTAURANTS"));

    verify(adminActivityService).getRecentActivity();
  }

  @Test
  @DisplayName("Recent activity: anonymous -> 401")
  void getRecentActivity_whenAnonymous_returnsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/admin/activity" + APIVersion.V20260509))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(adminActivityService);
  }
}
