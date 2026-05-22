package dev.christopherbell.location;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.location.model.ZipCoordinate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ZIP coordinate service")
class ZipCoordinateServiceTest {
  @Mock private ZipCoordinateGazetteerReader zipCoordinateGazetteerReader;
  @Mock private ZipCoordinateRepository zipCoordinateRepository;
  @InjectMocks private ZipCoordinateService zipCoordinateService;

  @Test
  void importRefreshCreatesUpdatesLeavesUnchangedAndDeletesStaleCensusRows() {
    when(zipCoordinateGazetteerReader.readBundledCensusData()).thenReturn(List.of(
        coordinate("78701", 30.271128, -97.743699),
        coordinate("70112", 29.956439, -90.074284),
        coordinate("94102", 37.779588, -122.419318)));
    when(zipCoordinateRepository.findAllBySource(eq("Census Gazetteer ZCTA"))).thenReturn(List.of(
        coordinate("70112", 1.0, 2.0),
        coordinate("94102", 37.779588, -122.419318),
        coordinate("99999", 9.0, 9.0)));

    var result = zipCoordinateService.importCensusZipCoordinates();

    assertEquals(3, result.processed());
    assertEquals(1, result.created());
    assertEquals(1, result.updated());
    assertEquals(1, result.unchanged());
    assertEquals(1, result.deleted());
    assertEquals("Census Gazetteer ZCTA", result.source());
    assertEquals(2025, result.sourceYear());

    @SuppressWarnings("unchecked")
    var savedCaptor = ArgumentCaptor.forClass(Iterable.class);
    verify(zipCoordinateRepository).saveAll(savedCaptor.capture());
    var saved = ((Iterable<ZipCoordinate>) savedCaptor.getValue()).iterator();
    assertEquals("78701", saved.next().getZipCode());
    assertEquals("70112", saved.next().getZipCode());

    @SuppressWarnings("unchecked")
    var deletedCaptor = ArgumentCaptor.forClass(Iterable.class);
    verify(zipCoordinateRepository).deleteAll(deletedCaptor.capture());
    var deleted = ((Iterable<ZipCoordinate>) deletedCaptor.getValue()).iterator();
    assertEquals("99999", deleted.next().getZipCode());
  }

  @Test
  void importDoesNotMutateCoordinatesWhenParsingFails() {
    when(zipCoordinateGazetteerReader.readBundledCensusData())
        .thenThrow(new IllegalStateException("malformed"));

    assertThrows(IllegalStateException.class, () -> zipCoordinateService.importCensusZipCoordinates());

    verifyNoInteractions(zipCoordinateRepository);
  }

  @Test
  void lookupNormalizesZipPlusFour() throws Exception {
    when(zipCoordinateRepository.findById(eq("78701")))
        .thenReturn(java.util.Optional.of(coordinate("78701", 30.271128, -97.743699)));

    var detail = zipCoordinateService.getZipCoordinate("78701-1234");

    assertEquals("78701", detail.zipCode());
    assertEquals(30.271128, detail.latitude());
    verify(zipCoordinateRepository).findById(eq("78701"));
  }

  @Test
  void lookupRejectsMalformedZipCodes() {
    assertThrows(InvalidRequestException.class, () -> zipCoordinateService.getZipCoordinate("zip"));

    verify(zipCoordinateRepository, never()).findById(any());
  }

  @Test
  void lookupReportsMissingImportedZipCoordinates() {
    when(zipCoordinateRepository.findById(eq("78701"))).thenReturn(java.util.Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> zipCoordinateService.getZipCoordinate("78701"));
  }

  private ZipCoordinate coordinate(String zipCode, double latitude, double longitude) {
    return ZipCoordinate.builder()
        .zipCode(zipCode)
        .latitude(latitude)
        .longitude(longitude)
        .source("Census Gazetteer ZCTA")
        .sourceYear(2025)
        .build();
  }
}
