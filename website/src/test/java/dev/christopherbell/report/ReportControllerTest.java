package dev.christopherbell.report;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.christopherbell.libs.api.controller.ControllerExceptionHandler;
import dev.christopherbell.libs.test.TestUtil;
import dev.christopherbell.report.model.ReportCreateRequest;
import dev.christopherbell.report.query.ReportQueryService;
import dev.christopherbell.report.query.ReportPage;
import dev.christopherbell.report.query.ReportQuery;
import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportStatus;
import dev.christopherbell.report.model.ReportTargetType;
import dev.christopherbell.report.model.ReportType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReportController.class)
@Import({ControllerExceptionHandler.class})
class ReportControllerTest {
  @Autowired private MockMvc mockMvc;
  @MockitoBean private ReportService reportService;
  @MockitoBean private ReportQueryService reportQueryService;

  @Test
  @DisplayName("Query reports: admin -> filtered stable page")
  @WithMockUser(authorities = {"ADMIN"})
  void queryReports_whenAdmin_returnsPage() throws Exception {
    var from = Instant.parse("2026-07-01T00:00:00Z");
    var to = Instant.parse("2026-07-26T23:59:59Z");
    var query = new ReportQuery(
        ReportStatus.OPEN, ReportType.SPAM, ReportTargetType.POST,
        "reader", from, to, 0, 25);
    when(reportQueryService.query(query)).thenReturn(new ReportPage(
        List.of(PostReport.builder().id("r1").build()), 0, 25, 1, 1));

    mockMvc.perform(get("/api/reports/2026-07-26")
            .param("status", "OPEN")
            .param("reportType", "SPAM")
            .param("targetType", "POST")
            .param("reporter", "reader")
            .param("from", from.toString())
            .param("to", to.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.payload.items[0].id").value("r1"))
        .andExpect(jsonPath("$.payload.totalElements").value(1));
  }

  @Test
  @DisplayName("Create report: authenticated -> 200 with success true")
  @WithMockUser(authorities = {"USER"})
  void testCreateReport_returnsOk() throws Exception {
    String request = TestUtil.readJsonAsString("/request/report-create-request.json");
    ReportCreateRequest requestObj =
        TestUtil.readJsonAsObject("/request/report-create-request.json", ReportCreateRequest.class);

    mockMvc.perform(
            post("/api/reports/2025-09-03")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));

    verify(reportService).submitReport(eq(requestObj));
  }

  @Test
  @DisplayName("Create report: blank post id returns 400 before service")
  @WithMockUser(authorities = {"USER"})
  void testCreateReport_whenPostIdBlank_returns400() throws Exception {
    String request = """
        {"postId":" ","reason":"spam"}
        """;

    mockMvc.perform(
            post("/api/reports/2025-09-03")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false));

    verifyNoInteractions(reportService);
  }
}
