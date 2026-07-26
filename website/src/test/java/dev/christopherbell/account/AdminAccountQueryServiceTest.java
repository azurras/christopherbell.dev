package dev.christopherbell.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.account.model.dto.AccountDetail;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@ExtendWith(MockitoExtension.class)
class AdminAccountQueryServiceTest {
  @Mock private MongoTemplate mongoTemplate;
  @Mock private AccountMapper accountMapper;
  private AdminAccountQueryService service;

  @BeforeEach
  void setUp() {
    service = new AdminAccountQueryService(mongoTemplate, accountMapper);
  }

  @Test
  @DisplayName("Admin account query applies stable paging, allowlisted sort, and literal search")
  void getAccounts_appliesValidatedQueryAndReturnsPageMetadata() throws Exception {
    var account = Account.builder()
        .id("account-1")
        .username("alpha")
        .status(AccountStatus.ACTIVE)
        .role(Role.USER)
        .build();
    var detail = AccountDetail.builder().id("account-1").username("alpha").build();
    var query = AdminAccountQuery.from(2, 25, "username", "desc", "ACTIVE", "USER", "a.b");
    when(mongoTemplate.count(any(Query.class), eq(Account.class))).thenReturn(51L);
    when(mongoTemplate.find(any(Query.class), eq(Account.class))).thenReturn(List.of(account));
    when(accountMapper.toAccount(account)).thenReturn(detail);

    var result = service.getAccounts(query);

    var countQuery = ArgumentCaptor.forClass(Query.class);
    var pageQuery = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).count(countQuery.capture(), eq(Account.class));
    verify(mongoTemplate).find(pageQuery.capture(), eq(Account.class));
    assertThat(countQuery.getValue().getQueryObject().toString())
        .contains("status", "ACTIVE", "role", "USER", "\\Qa.b\\E");
    assertThat(pageQuery.getValue().getSkip()).isEqualTo(50L);
    assertThat(pageQuery.getValue().getLimit()).isEqualTo(25);
    assertThat(pageQuery.getValue().getSortObject().toString()).contains("username=-1");
    assertThat(result.items()).containsExactly(detail);
    assertThat(result.page()).isEqualTo(2);
    assertThat(result.size()).isEqualTo(25);
    assertThat(result.totalElements()).isEqualTo(51L);
    assertThat(result.totalPages()).isEqualTo(3);
    assertThat(result.sort()).isEqualTo("username");
    assertThat(result.direction()).isEqualTo("DESC");
  }

  @Test
  @DisplayName("Admin account query rejects unsafe bounds and unknown sort values")
  void from_rejectsInvalidInputs() {
    assertThatThrownBy(() -> AdminAccountQuery.from(-1, 25, null, null, null, null, null))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessage("Account page must not be negative.");
    assertThatThrownBy(() -> AdminAccountQuery.from(0, 0, null, null, null, null, null))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessage("Account page size must be between 1 and 100.");
    assertThatThrownBy(() -> AdminAccountQuery.from(0, 25, "passwordHash", null, null, null, null))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessage("Unsupported account sort field.");
  }

  @Test
  @DisplayName("Admin account query accepts blank filters and caps search length")
  void from_normalizesBlankFiltersAndRejectsLongSearch() throws Exception {
    var query = AdminAccountQuery.from(null, null, null, null, " ", "", "  ");

    assertThat(query.page()).isZero();
    assertThat(query.size()).isEqualTo(25);
    assertThat(query.sort()).isEqualTo("createdOn");
    assertThat(query.direction().name()).isEqualTo("DESC");
    assertThat(query.status()).isNull();
    assertThat(query.role()).isNull();
    assertThat(query.text()).isNull();
    assertThatThrownBy(() -> AdminAccountQuery.from(
        0, 25, null, null, null, null, "x".repeat(101)))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessage("Account search text must not exceed 100 characters.");
  }
}
