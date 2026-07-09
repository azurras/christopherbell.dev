package dev.christopherbell.vehicle;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.christopherbell.libs.api.APIVersion;
import dev.christopherbell.libs.api.controller.ControllerExceptionHandler;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.test.TestUtil;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.vehicle.core.VehicleDataCollectionStateService;
import dev.christopherbell.vehicle.model.VehicleCreateRequest;
import dev.christopherbell.vehicle.model.VehicleDataCollectionState;
import dev.christopherbell.vehicle.model.VehicleUpdateRequest;
import dev.christopherbell.vehicle.model.VehicleVinBatchRequest;
import dev.christopherbell.vehicle.model.VehicleVinDecodeRequest;
import dev.christopherbell.vehicle.model.VehicleVinDecodeResponse;
import dev.christopherbell.vehicle.model.VehicleVinRequest;
import dev.christopherbell.vehicle.nhtsa.decode.VehicleVinDecodeService;
import dev.christopherbell.vehicle.nhtsa.model.NhtsaVinImportState;
import dev.christopherbell.vehicle.randomvin.model.RandomVinImportState;
import dev.christopherbell.vehicle.randomvin.model.RandomVinRobotsPolicyState;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(VehicleController.class)
@Import(ControllerExceptionHandler.class)
public class VehicleControllerTest {
  @Autowired private MockMvc mockMvc;
  @MockitoBean private PermissionService permissionService;
  @MockitoBean private VehicleDataCollectionStateService vehicleDataCollectionStateService;
  @MockitoBean private VehicleVinDecodeService vehicleVinDecodeService;
  @MockitoBean private VehicleService vehicleService;

  @Test
  @DisplayName("Creates vehicle when caller has ADMIN authority")
  @WithMockUser(authorities = {"ADMIN"})
  public void testCreateVehicle() throws Exception {
    var request = TestUtil.readJsonAsString("/request/vehicle-create-request.json");
    var requestObject =
        TestUtil.readJsonAsObject("/request/vehicle-create-request.json", VehicleCreateRequest.class);
    var response = VehicleStub.getVehicleDetailStub(VehicleStub.ID);

    when(vehicleService.createVehicle(eq(requestObject))).thenReturn(response);

    mockMvc
        .perform(post("/api/vehicles" + APIVersion.V20260509)
            .with(csrf())
            .content(request)
            .contentType(MediaType.APPLICATION_JSON_VALUE))
        .andExpect(status().isCreated())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.id").value(VehicleStub.ID))
        .andExpect(jsonPath("$.payload.vin").value(VehicleStub.VIN))
        .andExpect(jsonPath("$.payload.createdOn").value("2026-01-18T02:30:00.809+00:00"))
        .andExpect(jsonPath("$.payload.lastUpdatedOn").value("2026-01-18T02:35:00.809+00:00"))
        .andExpect(jsonPath("$.payload.make").value(VehicleStub.MAKE))
        .andExpect(jsonPath("$.payload.model").value(VehicleStub.MODEL))
        .andExpect(jsonPath("$.payload.year").value(VehicleStub.YEAR))
        .andExpect(jsonPath("$.payload.trim").value(VehicleStub.TRIM));

    verify(vehicleService).createVehicle(eq(requestObject));
  }

  @Test
  @DisplayName("Rejects vehicle creation without authentication")
  public void testCreateVehicle_whenUnauthenticated_Returns401() throws Exception {
    mockMvc
        .perform(post("/api/vehicles" + APIVersion.V20260509)
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON_VALUE))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(vehicleService);
  }

  @Test
  @DisplayName("Creates vehicle from VIN when caller has ADMIN authority")
  @WithMockUser(authorities = {"ADMIN"})
  public void testCreateVehicleFromVin() throws Exception {
    var request = """
        {"vin":"%s"}
        """.formatted(VehicleStub.VIN);
    var requestObject = new VehicleVinRequest(VehicleStub.VIN);
    var response = VehicleStub.getVehicleDetailStub(VehicleStub.ID);

    when(vehicleService.createVehicleFromVin(eq(requestObject))).thenReturn(response);

    mockMvc
        .perform(post("/api/vehicles" + APIVersion.V20260509 + "/vin")
            .with(csrf())
            .content(request)
            .contentType(MediaType.APPLICATION_JSON_VALUE))
        .andExpect(status().isCreated())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.id").value(VehicleStub.ID))
        .andExpect(jsonPath("$.payload.vin").value(VehicleStub.VIN));

    verify(vehicleService).createVehicleFromVin(eq(requestObject));
  }

  @Test
  @DisplayName("Rejects VIN-only vehicle creation without authentication")
  public void testCreateVehicleFromVin_whenUnauthenticated_Returns401() throws Exception {
    mockMvc
        .perform(post("/api/vehicles" + APIVersion.V20260509 + "/vin")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON_VALUE))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(vehicleService);
  }

  @Test
  @DisplayName("Decodes VIN through NHTSA wrapper")
  @WithMockUser
  public void testDecodeVin() throws Exception {
    var request = """
        {"vin":"%s"}
        """.formatted(VehicleStub.VIN);
    var requestObject = new VehicleVinDecodeRequest(VehicleStub.VIN);
    var response = VehicleVinDecodeResponse.builder()
        .vin(VehicleStub.VIN)
        .make(VehicleStub.MAKE)
        .model(VehicleStub.MODEL)
        .year(VehicleStub.YEAR)
        .body("Coupe")
        .plantCity("MARYSVILLE")
        .plantState("OHIO")
        .plantCountry("UNITED STATES (USA)")
        .rawDecodedValues(java.util.Map.of("VIN", VehicleStub.VIN))
        .build();

    when(vehicleVinDecodeService.decode(eq(requestObject), anyString())).thenReturn(response);

    mockMvc
        .perform(post("/api/vehicles" + APIVersion.V20260509 + "/vin/decode")
            .with(csrf())
            .content(request)
            .contentType(MediaType.APPLICATION_JSON_VALUE))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.vin").value(VehicleStub.VIN))
        .andExpect(jsonPath("$.payload.make").value(VehicleStub.MAKE))
        .andExpect(jsonPath("$.payload.model").value(VehicleStub.MODEL))
        .andExpect(jsonPath("$.payload.year").value(VehicleStub.YEAR))
        .andExpect(jsonPath("$.payload.body").value("Coupe"))
        .andExpect(jsonPath("$.payload.plantCity").value("MARYSVILLE"));

    verify(vehicleVinDecodeService).decode(eq(requestObject), eq("account:user"));
  }

  @Test
  @DisplayName("Creates vehicles from VINs when caller has ADMIN authority")
  @WithMockUser(authorities = {"ADMIN"})
  public void testCreateVehiclesFromVins() throws Exception {
    var vin2 = "2FMDK4JCXEBB62196";
    var request = """
        {"vins":["%s","%s"]}
        """.formatted(VehicleStub.VIN, vin2);
    var requestObject = new VehicleVinBatchRequest(List.of(VehicleStub.VIN, vin2));
    var response = List.of(
        VehicleStub.getVehicleDetailStub(VehicleStub.ID),
        VehicleStub.getVehicleDetailStub(VehicleStub.ID_2)
    );
    response.get(1).setVin(vin2);

    when(vehicleService.createVehiclesFromVins(eq(requestObject))).thenReturn(response);

    mockMvc
        .perform(post("/api/vehicles" + APIVersion.V20260509 + "/vins")
            .with(csrf())
            .content(request)
            .contentType(MediaType.APPLICATION_JSON_VALUE))
        .andExpect(status().isCreated())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload[0].vin").value(VehicleStub.VIN))
        .andExpect(jsonPath("$.payload[1].vin").value(vin2));

    verify(vehicleService).createVehiclesFromVins(eq(requestObject));
  }

  @Test
  @DisplayName("Rejects batch VIN vehicle creation without authentication")
  public void testCreateVehiclesFromVins_whenUnauthenticated_Returns401() throws Exception {
    mockMvc
        .perform(post("/api/vehicles" + APIVersion.V20260509 + "/vins")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON_VALUE))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(vehicleService);
  }

  @Test
  @DisplayName("Returns all vehicles")
  @WithMockUser(authorities = {"ADMIN"})
  public void testGetVehicles() throws Exception {
    var response = List.of(VehicleStub.getVehicleDetailStub(VehicleStub.ID));
    when(vehicleService.getVehicles()).thenReturn(response);

    mockMvc
        .perform(get("/api/vehicles" + APIVersion.V20260509)
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload[0].id").value(VehicleStub.ID))
        .andExpect(jsonPath("$.payload[0].make").value(VehicleStub.MAKE));

    verify(vehicleService).getVehicles();
  }

  @Test
  @DisplayName("Returns vehicle data collection state")
  @WithMockUser(authorities = {"ADMIN"})
  public void testGetDataCollectionState() throws Exception {
    var state = VehicleDataCollectionState.builder()
        .randomVin(RandomVinImportState.builder()
            .id("randomvin")
            .callsToday(2)
            .lifetimeCalls(5L)
            .vinsProcessedToday(1)
            .lifetimeVinsProcessed(4L)
            .notes("VIN data sourced from randomvin.com")
            .robotsPolicy(RandomVinRobotsPolicyState.builder()
                .allowed(true)
                .reason("no_matching_disallow")
                .failClosed(true)
                .build())
            .build())
        .nhtsa(NhtsaVinImportState.builder()
            .id("nhtsa")
            .callsToday(1)
            .lifetimeCalls(3L)
            .vinsProcessedToday(50)
            .lifetimeVinsProcessed(150L)
            .notes("VIN enrichment data sourced from NHTSA vPIC")
            .build())
        .build();
    when(vehicleDataCollectionStateService.getState()).thenReturn(state);

    mockMvc
        .perform(get("/api/vehicles" + APIVersion.V20260509 + "/data-collection-state")
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.randomVin.id").value("randomvin"))
        .andExpect(jsonPath("$.payload.randomVin.lifetimeCalls").value(5))
        .andExpect(jsonPath("$.payload.randomVin.lifetimeVinsProcessed").value(4))
        .andExpect(jsonPath("$.payload.randomVin.notes").value("VIN data sourced from randomvin.com"))
        .andExpect(jsonPath("$.payload.randomVin.robotsPolicy.allowed").value(true))
        .andExpect(jsonPath("$.payload.randomVin.robotsPolicy.reason").value("no_matching_disallow"))
        .andExpect(jsonPath("$.payload.randomVin.robotsPolicy.failClosed").value(true))
        .andExpect(jsonPath("$.payload.nhtsa.id").value("nhtsa"))
        .andExpect(jsonPath("$.payload.nhtsa.lifetimeCalls").value(3))
        .andExpect(jsonPath("$.payload.nhtsa.lifetimeVinsProcessed").value(150))
        .andExpect(jsonPath("$.payload.nhtsa.notes").value("VIN enrichment data sourced from NHTSA vPIC"));

    verify(vehicleDataCollectionStateService).getState();
  }

  @Test
  @DisplayName("Returns vehicles by make")
  @WithMockUser(authorities = {"ADMIN"})
  public void testGetVehiclesByMake() throws Exception {
    var response = List.of(VehicleStub.getVehicleDetailStub(VehicleStub.ID));
    when(vehicleService.getVehiclesByMake(eq(VehicleStub.MAKE))).thenReturn(response);

    mockMvc
        .perform(get("/api/vehicles" + APIVersion.V20260509 + "/make/{make}", VehicleStub.MAKE)
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload[0].vin").value(VehicleStub.VIN));

    verify(vehicleService).getVehiclesByMake(eq(VehicleStub.MAKE));
  }

  @Test
  @DisplayName("Returns 400 when make is invalid")
  @WithMockUser(authorities = {"ADMIN"})
  public void testGetVehiclesByMake_whenInvalidMake_Returns400() throws Exception {
    when(vehicleService.getVehiclesByMake(eq(VehicleStub.MAKE)))
        .thenThrow(new InvalidRequestException("Vehicle make cannot be null or blank."));

    mockMvc
        .perform(get("/api/vehicles" + APIVersion.V20260509 + "/make/{make}", VehicleStub.MAKE)
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());

    verify(vehicleService).getVehiclesByMake(eq(VehicleStub.MAKE));
  }

  @Test
  @DisplayName("Returns vehicle by id")
  @WithMockUser(authorities = {"ADMIN"})
  public void testGetVehicleById() throws Exception {
    var response = VehicleStub.getVehicleDetailStub(VehicleStub.ID);
    when(vehicleService.getVehicleById(eq(VehicleStub.ID))).thenReturn(response);

    mockMvc
        .perform(get("/api/vehicles" + APIVersion.V20260509 + "/{id}", VehicleStub.ID)
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.id").value(VehicleStub.ID));

    verify(vehicleService).getVehicleById(eq(VehicleStub.ID));
  }

  @Test
  @DisplayName("Returns 404 when vehicle does not exist")
  @WithMockUser(authorities = {"ADMIN"})
  public void testGetVehicleById_whenNotFound_Returns404() throws Exception {
    when(vehicleService.getVehicleById(eq(VehicleStub.ID)))
        .thenThrow(new ResourceNotFoundException("Vehicle not found: " + VehicleStub.ID));

    mockMvc
        .perform(get("/api/vehicles" + APIVersion.V20260509 + "/{id}", VehicleStub.ID)
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound());

    verify(vehicleService).getVehicleById(eq(VehicleStub.ID));
  }

  @Test
  @DisplayName("Returns standard 500 envelope for unexpected exceptions")
  @WithMockUser(authorities = {"ADMIN"})
  public void testGetVehicleById_whenUnexpectedException_Returns500Envelope() throws Exception {
    when(vehicleService.getVehicleById(eq(VehicleStub.ID)))
        .thenThrow(new RuntimeException("database unavailable"));

    mockMvc
        .perform(get("/api/vehicles" + APIVersion.V20260509 + "/{id}", VehicleStub.ID)
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.messages[0].code").value("INTERNAL_SERVER_ERROR"))
        .andExpect(jsonPath("$.messages[0].description")
            .value("An unexpected error occurred. Please try again later."));

    verify(vehicleService).getVehicleById(eq(VehicleStub.ID));
  }

  @Test
  @DisplayName("Updates vehicle")
  @WithMockUser(authorities = {"ADMIN"})
  public void testUpdateVehicle() throws Exception {
    var request = TestUtil.readJsonAsString("/request/vehicle-update-request.json");
    var requestObject =
        TestUtil.readJsonAsObject("/request/vehicle-update-request.json", VehicleUpdateRequest.class);
    var response = VehicleStub.getVehicleDetailStub(VehicleStub.ID);

    when(vehicleService.updateVehicle(eq(VehicleStub.ID), eq(requestObject))).thenReturn(response);

    mockMvc
        .perform(put("/api/vehicles" + APIVersion.V20260509 + "/{id}", VehicleStub.ID)
            .with(csrf())
            .content(request)
            .contentType(MediaType.APPLICATION_JSON_VALUE)
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.id").value(VehicleStub.ID));

    verify(vehicleService).updateVehicle(eq(VehicleStub.ID), eq(requestObject));
  }

  @Test
  @DisplayName("Deletes vehicle")
  @WithMockUser(authorities = {"ADMIN"})
  public void testDeleteVehicleById() throws Exception {
    var response = VehicleStub.getVehicleDetailStub(VehicleStub.ID);
    when(vehicleService.deleteVehicleById(eq(VehicleStub.ID))).thenReturn(response);

    mockMvc
        .perform(delete("/api/vehicles" + APIVersion.V20260509 + "/{id}", VehicleStub.ID)
            .with(csrf())
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.id").value(VehicleStub.ID));

    verify(vehicleService).deleteVehicleById(eq(VehicleStub.ID));
  }
}
