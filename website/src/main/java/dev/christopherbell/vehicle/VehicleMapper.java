package dev.christopherbell.vehicle;

import dev.christopherbell.vehicle.model.Vehicle;
import dev.christopherbell.vehicle.model.VehicleCreateRequest;
import dev.christopherbell.vehicle.model.VehicleDetail;
import dev.christopherbell.vehicle.model.VehicleUpdateRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Mapper for vehicle entities and API DTOs.
 */
@Mapper(componentModel = "spring")
public interface VehicleMapper {
  VehicleDetail toVehicleDetail(Vehicle vehicle);

  Vehicle toVehicle(VehicleDetail vehicleDetail);

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "bodyClass", ignore = true)
  @Mapping(target = "createdBy", ignore = true)
  @Mapping(target = "createdOn", ignore = true)
  @Mapping(target = "doors", ignore = true)
  @Mapping(target = "gvwr", ignore = true)
  @Mapping(target = "lastModifiedBy", ignore = true)
  @Mapping(target = "lastUpdatedOn", ignore = true)
  @Mapping(target = "manufacturer", ignore = true)
  @Mapping(target = "manufacturerId", ignore = true)
  @Mapping(target = "nhtsaDecodedValues", ignore = true)
  @Mapping(target = "nhtsaErrorCode", ignore = true)
  @Mapping(target = "nhtsaErrorText", ignore = true)
  @Mapping(target = "nhtsaLastDecodedOn", ignore = true)
  @Mapping(target = "plantCity", ignore = true)
  @Mapping(target = "plantCountry", ignore = true)
  @Mapping(target = "plantState", ignore = true)
  @Mapping(target = "series", ignore = true)
  @Mapping(target = "vehicleType", ignore = true)
  Vehicle toVehicle(VehicleCreateRequest request);

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "bodyClass", ignore = true)
  @Mapping(target = "createdBy", ignore = true)
  @Mapping(target = "createdOn", ignore = true)
  @Mapping(target = "doors", ignore = true)
  @Mapping(target = "gvwr", ignore = true)
  @Mapping(target = "lastModifiedBy", ignore = true)
  @Mapping(target = "lastUpdatedOn", ignore = true)
  @Mapping(target = "manufacturer", ignore = true)
  @Mapping(target = "manufacturerId", ignore = true)
  @Mapping(target = "nhtsaDecodedValues", ignore = true)
  @Mapping(target = "nhtsaErrorCode", ignore = true)
  @Mapping(target = "nhtsaErrorText", ignore = true)
  @Mapping(target = "nhtsaLastDecodedOn", ignore = true)
  @Mapping(target = "plantCity", ignore = true)
  @Mapping(target = "plantCountry", ignore = true)
  @Mapping(target = "plantState", ignore = true)
  @Mapping(target = "series", ignore = true)
  @Mapping(target = "vehicleType", ignore = true)
  Vehicle toVehicle(VehicleUpdateRequest request);
}
