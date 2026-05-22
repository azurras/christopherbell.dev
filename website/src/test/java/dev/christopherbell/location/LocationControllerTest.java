package dev.christopherbell.location;

import dev.christopherbell.configuration.SecurityConfig;
import dev.christopherbell.libs.api.controller.ControllerExceptionHandler;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.location.model.ZipCoordinateDetail;
import dev.christopherbell.location.model.ZipCoordinateImportResult;
import dev.christopherbell.permission.PermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LocationController.class)
@Import({ControllerExceptionHandler.class, SecurityConfig.class})
@DisplayName("Location controller")
class LocationControllerTest {
  @Autowired private MockMvc mockMvc;
  @MockitoBean(name = "permissionService") private PermissionService permissionService;
  @MockitoBean private ZipCoordinateService zipCoordinateService;

  @Test
  void publicZipLookupReturnsCoordinates() throws Exception {
    when(zipCoordinateService.getZipCoordinate(eq("78701")))
        .thenReturn(ZipCoordinateDetail.builder()
            .zipCode("78701")
            .latitude(30.271128)
            .longitude(-97.743699)
            .source("Census Gazetteer ZCTA")
            .sourceYear(2025)
            .build());

    mockMvc.perform(get("/api/location/zip/78701"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.zipCode").value("78701"))
        .andExpect(jsonPath("$.payload.latitude").value(30.271128))
        .andExpect(jsonPath("$.payload.longitude").value(-97.743699))
        .andExpect(jsonPath("$.payload.source").value("Census Gazetteer ZCTA"))
        .andExpect(jsonPath("$.payload.sourceYear").value(2025));

    verify(zipCoordinateService).getZipCoordinate(eq("78701"));
  }

  @Test
  void publicZipLookupRejectsMalformedZipCodes() throws Exception {
    when(zipCoordinateService.getZipCoordinate(eq("bad")))
        .thenThrow(new InvalidRequestException("ZIP code must be valid."));

    mockMvc.perform(get("/api/location/zip/bad"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void publicZipLookupReturnsNotFoundForMissingImportedZipCodes() throws Exception {
    when(zipCoordinateService.getZipCoordinate(eq("78701")))
        .thenThrow(new ResourceNotFoundException("ZIP coordinate not found."));

    mockMvc.perform(get("/api/location/zip/78701"))
        .andExpect(status().isNotFound());
  }

  @Test
  @WithMockUser(authorities = {"ADMIN"})
  void adminCanImportCensusZipCoordinates() throws Exception {
    when(permissionService.hasAuthority(eq("ADMIN"))).thenReturn(true);
    when(zipCoordinateService.importCensusZipCoordinates())
        .thenReturn(ZipCoordinateImportResult.builder()
            .processed(3)
            .created(1)
            .updated(1)
            .unchanged(1)
            .deleted(1)
            .source("Census Gazetteer ZCTA")
            .sourceYear(2025)
            .build());

    mockMvc.perform(post("/api/location/zip/import/census"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.processed").value(3))
        .andExpect(jsonPath("$.payload.created").value(1))
        .andExpect(jsonPath("$.payload.updated").value(1))
        .andExpect(jsonPath("$.payload.unchanged").value(1))
        .andExpect(jsonPath("$.payload.deleted").value(1));

    verify(zipCoordinateService).importCensusZipCoordinates();
  }
}
