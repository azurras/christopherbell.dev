package dev.christopherbell.survive;

import dev.christopherbell.survive.model.SurviveRequests;
import dev.christopherbell.survive.model.SurviveSnapshot;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Browser transport for the Java world; existing site CSRF protection guards every mutation. */
@RestController
@RequestMapping("/api/survive/v1")
public class SurviveController {
  static final String COOKIE_NAME = "CBELL_SURVIVOR";
  private final SurviveService survivors;

  public SurviveController(SurviveService survivors) {
    this.survivors = survivors;
  }

  /** Reads a current survivor or returns 204 so the browser can show its join form. */
  @GetMapping("/game")
  public ResponseEntity<SurviveSnapshot> getGame(
      @CookieValue(name = COOKIE_NAME, required = false) String token) {
    var state = survivors.find(token);
    return state.map(snapshot -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(snapshot))
        .orElseGet(() -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build());
  }

  /** Joins or restarts this survivor without resetting anyone else's game. */
  @PostMapping("/game")
  public ResponseEntity<SurviveSnapshot> joinGame(
      @CookieValue(name = COOKIE_NAME, required = false) String previousToken,
      @Valid @RequestBody SurviveRequests.Join request, HttpServletRequest httpRequest) {
    var joined = survivors.join(previousToken, request.name());
    // A session cookie survives page refresh; the server enforces sliding idle expiry.
    var cookie = ResponseCookie.from(COOKIE_NAME, joined.token()).httpOnly(true)
        .secure(httpRequest.isSecure()).sameSite("Strict").path("/api/survive/v1").build();
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .header(HttpHeaders.SET_COOKIE, cookie.toString()).body(joined.state());
  }

  /** Executes only a validated command and revision; state is always computed by Java. */
  @PostMapping("/actions")
  public ResponseEntity<SurviveSnapshot> performAction(
      @CookieValue(name = COOKIE_NAME, required = false) String token,
      @Valid @RequestBody SurviveRequests.Act request) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(survivors.act(token, request.action(), request.revision()));
  }

  /** Transfers supplies between survivors in the same authoritative world. */
  @PostMapping("/gifts")
  public ResponseEntity<SurviveSnapshot> giveSupplies(
      @CookieValue(name = COOKIE_NAME, required = false) String token,
      @Valid @RequestBody SurviveRequests.Gift request) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(survivors.giveSupplies(token, request.recipientId(), request.resource(),
            request.quantity(), request.revision()));
  }
}
