package dev.christopherbell.sitemonitor.api;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.christopherbell.sitemonitor.model.MonitorWorkspace;
import dev.christopherbell.sitemonitor.monitor.SiteMonitorService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.Import;
import dev.christopherbell.configuration.security.ControllerSliceMethodSecurityTestConfig;

@WebMvcTest(SiteMonitorController.class)
@Import(ControllerSliceMethodSecurityTestConfig.class)
class SiteMonitorControllerTest {
  @Autowired MockMvc mvc;
  @MockitoBean SiteMonitorService service;

  @Test void anonymousCannotReadWorkspaceOrReport() throws Exception {
    mvc.perform(get("/api/site-monitor/v1")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/site-monitor/v1/sites/site/reports/report"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(service);
  }
  @Test @WithMockUser void mutationRequiresCsrf() throws Exception {
    mvc.perform(post("/api/site-monitor/v1/sites/site/baseline")).andExpect(status().is4xxClientError());
    mvc.perform(delete("/api/site-monitor/v1/sites/site")).andExpect(status().is4xxClientError());
    verifyNoInteractions(service);
  }
  @Test @WithMockUser void workspaceAndReportArePrivateAndTextOnly() throws Exception {
    when(service.currentWorkspace()).thenReturn(new MonitorWorkspace(null, null, "owner", List.of()));
    mvc.perform(get("/api/site-monitor/v1")).andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.success").value(true));
    when(service.report("site", "report")).thenReturn("Website Monitor report");
    mvc.perform(get("/api/site-monitor/v1/sites/site/reports/report")).andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "text/plain; charset=UTF-8"))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Content-Disposition", "attachment; filename=website-monitor-report.txt"));
  }
  @Test @WithMockUser void cooldownIsCategorizedAndInvalidInputNeverCallsService() throws Exception {
    when(service.run("site", false)).thenThrow(new MonitorProblem(429, "Wait before checking again."));
    mvc.perform(post("/api/site-monitor/v1/sites/site/check").with(csrf()))
        .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "900"))
        .andExpect(jsonPath("$.success").value(false));
    clearInvocations(service);
    mvc.perform(post("/api/site-monitor/v1/sites").with(csrf()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"label\":\"" + "x".repeat(81) + "\",\"demo\":true}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }
}
