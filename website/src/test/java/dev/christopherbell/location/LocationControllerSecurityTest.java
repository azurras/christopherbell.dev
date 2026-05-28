package dev.christopherbell.location;

import dev.christopherbell.configuration.security.SecurityConfig;
import dev.christopherbell.libs.api.controller.ControllerExceptionHandler;
import dev.christopherbell.location.model.ZipCoordinateDetail;
import dev.christopherbell.location.zip.LocationController;
import dev.christopherbell.location.zip.ZipCoordinateService;
import dev.christopherbell.permission.PermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LocationController.class)
@Import({ControllerExceptionHandler.class, SecurityConfig.class})
@DisplayName("Location controller security")
class LocationControllerSecurityTest {
  @Autowired private MockMvc mockMvc;
  @MockitoBean(name = "permissionService") private PermissionService permissionService;
  @MockitoBean private ZipCoordinateService zipCoordinateService;

  @Test
  void anonymousZipLookupIsPublic() throws Exception {
    when(zipCoordinateService.getZipCoordinate(eq("78701")))
        .thenReturn(ZipCoordinateDetail.builder()
            .zipCode("78701")
            .latitude(30.271128)
            .longitude(-97.743699)
            .source("Census Gazetteer ZCTA")
            .sourceYear(2025)
            .build());

    mockMvc.perform(get("/api/location/zip/78701"))
        .andExpect(status().isOk());
  }

  @Test
  void anonymousZipImportIsRejected() throws Exception {
    mockMvc.perform(post("/api/location/zip/import/census"))
        .andExpect(status().isForbidden());
  }
}
