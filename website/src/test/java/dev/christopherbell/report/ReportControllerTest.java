package dev.christopherbell.report;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.christopherbell.libs.api.controller.ControllerExceptionHandler;
import dev.christopherbell.libs.test.TestUtil;
import dev.christopherbell.report.model.ReportCreateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
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
}
