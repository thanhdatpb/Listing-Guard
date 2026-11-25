package com.thanhdatpb.listingguard.api.console.editor;

import com.thanhdatpb.listingguard.api.client.Client;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRoles;
import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import com.thanhdatpb.listingguard.api.policy.PolicyCheckRequest;
import com.thanhdatpb.listingguard.api.policy.PolicyChecks;
import com.thanhdatpb.listingguard.api.web.Problems;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Session-authenticated entry point for the same policy checks used by the API-key controller. */
@Hidden
@RestController
@ConditionalOnProperty(name = {"listing-guard.console.enabled", "listing-guard.analyzer.enabled"}, havingValue = "true")
class ConsolePolicyCheckController {
    private final PolicyChecks checks;
    private final ConsoleAccountRoles accounts;
    private final ClientRepository clients;

    ConsolePolicyCheckController(PolicyChecks checks, ConsoleAccountRoles accounts, ClientRepository clients) {
        this.checks = checks;
        this.accounts = accounts;
        this.clients = clients;
    }

    @PostMapping("/console/check/edit/policy-checks")
    ResponseEntity<PolicyChecks.Result> check(@AuthenticationPrincipal ConsoleUser user, @Valid @RequestBody PolicyCheckRequest input) {
        if (user == null || accounts.account(user).isEmpty() || clients.findById(user.clientId()).filter(Client::isActive).isEmpty()) {
            throw new PolicyChecks.Failure(HttpStatus.FORBIDDEN, "policy-session-forbidden", "No active account for the policy check.");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(checks.check(user.clientId(), input));
    }

    @ExceptionHandler(PolicyChecks.Failure.class)
    ProblemDetail failure(PolicyChecks.Failure failure) {
        return Problems.of(failure.status(), failure.code(), failure.getMessage());
    }
}
