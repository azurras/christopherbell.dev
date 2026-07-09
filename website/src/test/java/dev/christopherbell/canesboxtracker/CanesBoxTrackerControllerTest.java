package dev.christopherbell.canesboxtracker;

import dev.christopherbell.canesboxtracker.model.CanesBoxTrackerHistory;
import dev.christopherbell.canesboxtracker.model.CanesBoxWeeklyPriceDetail;
import dev.christopherbell.configuration.security.ControllerSliceMethodSecurityTestConfig;
import dev.christopherbell.libs.api.controller.ControllerExceptionHandler;
import dev.christopherbell.permission.PermissionService;
import java.math.BigDecimal;
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

import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CanesBoxTrackerController.class)
@Import({ControllerExceptionHandler.class, ControllerSliceMethodSecurityTestConfig.class})
@DisplayName("Raising Canes Box Index controller")
class CanesBoxTrackerControllerTest {
  @Autowired private MockMvc mockMvc;
  @MockitoBean(name = "permissionService") private PermissionService permissionService;
  @MockitoBean private CanesBoxTrackerService service;

  @Test
  void publicHistoryReturnsWeeklyPriceHistory() throws Exception {
    var latest = new CanesBoxWeeklyPriceDetail(
        "2026-06-01",
        Instant.parse("2026-06-04T12:00:00Z"),
        new BigDecimal("13.24"),
        "USD",
        14,
        15,
        14,
        0,
        1,
        List.of());
    when(service.getHistory()).thenReturn(new CanesBoxTrackerHistory(latest, List.of(latest)));

    mockMvc.perform(get("/api/canes-box-tracker/2026-06-04/history"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.latest.weekStartDate").value("2026-06-01"))
        .andExpect(jsonPath("$.payload.latest.averagePrice").value(13.24))
        .andExpect(jsonPath("$.payload.latest.successfulMetroCount").value(14))
        .andExpect(jsonPath("$.payload.latest.verifiedMetroCount").value(14))
        .andExpect(jsonPath("$.payload.weeks[0].currency").value("USD"));
  }

  @Test
  @WithMockUser
  void adminCollectPullsNewBoxIndexSnapshot() throws Exception {
    var detail = new CanesBoxWeeklyPriceDetail(
        "2026-06-01",
        Instant.parse("2026-06-04T12:00:00Z"),
        new BigDecimal("13.24"),
        "USD",
        14,
        15,
        14,
        0,
        1,
        List.of());
    when(permissionService.hasAuthority("ADMIN")).thenReturn(true);
    when(service.collectCurrentWeekForAdmin()).thenReturn(detail);

    mockMvc.perform(post("/api/canes-box-tracker/2026-06-04/collect")
            .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.weekStartDate").value("2026-06-01"))
        .andExpect(jsonPath("$.payload.averagePrice").value(13.24))
        .andExpect(jsonPath("$.payload.successfulMetroCount").value(14))
        .andExpect(jsonPath("$.payload.verifiedMetroCount").value(14))
        .andExpect(jsonPath("$.payload.totalMetroCount").value(15));
  }

  @Test
  @WithMockUser
  void adminCollectRejectsNonAdmins() throws Exception {
    when(permissionService.hasAuthority("ADMIN")).thenReturn(false);

    mockMvc.perform(post("/api/canes-box-tracker/2026-06-04/collect")
            .with(csrf()))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser
  void adminApprovePromotesProvisionalMetroPrice() throws Exception {
    var detail = detail("2026-06-01");
    when(permissionService.hasAuthority("ADMIN")).thenReturn(true);
    when(service.approveMetroPrice(eq("2026-06-01"), eq("Dallas-Fort Worth"), eq("Receipt checked.")))
        .thenReturn(detail);

    mockMvc.perform(post("/api/canes-box-tracker/2026-06-04/2026-06-01/metros/Dallas-Fort Worth/approve")
            .with(csrf())
            .contentType("application/json")
            .content("{\"note\":\"Receipt checked.\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.payload.weekStartDate").value("2026-06-01"))
        .andExpect(jsonPath("$.payload.verifiedMetroCount").value(14));
  }

  @Test
  @WithMockUser
  void adminRejectExcludesMetroPrice() throws Exception {
    var detail = detail("2026-06-01");
    when(permissionService.hasAuthority("ADMIN")).thenReturn(true);
    when(service.rejectMetroPrice(eq("2026-06-01"), eq("Dallas-Fort Worth"), eq("Stale menu.")))
        .thenReturn(detail);

    mockMvc.perform(post("/api/canes-box-tracker/2026-06-04/2026-06-01/metros/Dallas-Fort Worth/reject")
            .with(csrf())
            .contentType("application/json")
            .content("{\"note\":\"Stale menu.\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.payload.weekStartDate").value("2026-06-01"));
  }

  @Test
  @WithMockUser
  void adminManualPriceCreatesVerifiedDatapoint() throws Exception {
    var detail = detail("2026-06-01");
    when(permissionService.hasAuthority("ADMIN")).thenReturn(true);
    when(service.recordManualVerifiedPrice(eq("Dallas-Fort Worth"), any(BigDecimal.class), eq("https://receipt.example/dallas"), eq("Receipt checked.")))
        .thenReturn(detail);

    mockMvc.perform(post("/api/canes-box-tracker/2026-06-04/manual-prices")
            .with(csrf())
            .contentType("application/json")
            .content("""
                {
                  "metroName": "Dallas-Fort Worth",
                  "price": 12.49,
                  "sourceUrl": "https://receipt.example/dallas",
                  "note": "Receipt checked."
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.payload.weekStartDate").value("2026-06-01"))
        .andExpect(jsonPath("$.payload.averagePrice").value(13.24));
  }

  private CanesBoxWeeklyPriceDetail detail(String weekStartDate) {
    return new CanesBoxWeeklyPriceDetail(
        weekStartDate,
        Instant.parse("2026-06-04T12:00:00Z"),
        new BigDecimal("13.24"),
        "USD",
        14,
        15,
        14,
        0,
        1,
        List.of());
  }
}
