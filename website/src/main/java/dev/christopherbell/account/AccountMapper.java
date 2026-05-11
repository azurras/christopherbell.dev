package dev.christopherbell.account;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.dto.AccountDetail;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Mapper interface for converting between Account entity and AccountDetail DTO.
 */
@Mapper(componentModel = "spring")
public interface AccountMapper {

  /**
   * Maps AccountDetail DTO to Account entity.
   * Ignores fields that should not be set during this mapping.
   *
   * @param accountDetail the AccountDetail DTO
   * @return the mapped Account entity
   */
  @Mapping(target = "inviteCode", ignore = true)
  @Mapping(target = "inviteCodeOwner", ignore = true)
  @Mapping(target = "loginToken", ignore = true)
  @Mapping(target = "passwordSalt", ignore = true)
  @Mapping(target = "passwordHash", ignore = true)
  @Mapping(target = "followingIds", ignore = true)
  Account toAccountEntity(AccountDetail accountDetail);

  /**
   * Maps Account entity to AccountDetail DTO.
   *
   * @param account the Account entity
   * @return the mapped AccountDetail DTO
   */
  AccountDetail toAccount(Account account);
}
