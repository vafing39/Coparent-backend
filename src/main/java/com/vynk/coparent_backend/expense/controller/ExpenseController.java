package com.vynk.coparent_backend.expense.controller;

import com.vynk.coparent_backend.expense.dto.BalanceResponse;
import com.vynk.coparent_backend.expense.dto.ContestRequest;
import com.vynk.coparent_backend.expense.dto.ExpenseRequest;
import com.vynk.coparent_backend.expense.dto.ExpenseResponse;
import com.vynk.coparent_backend.expense.dto.SettlementRequest;
import com.vynk.coparent_backend.expense.dto.SettlementResponse;
import com.vynk.coparent_backend.expense.service.BalanceService;
import com.vynk.coparent_backend.expense.service.ExpenseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Dépenses partagées, solde courant et virements entre les deux parents. */
@RestController
@RequestMapping("/api/v1/families/{familyId}/expenses")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseService expenseService;
    private final BalanceService balanceService;

    @GetMapping
    public List<ExpenseResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId) {
        return expenseService.list(familyId, userId(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                  @Valid @RequestBody ExpenseRequest request) {
        return expenseService.create(familyId, userId(jwt), request);
    }

    @GetMapping("/{expenseId}")
    public ExpenseResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                               @PathVariable UUID expenseId) {
        return expenseService.get(familyId, userId(jwt), expenseId);
    }

    @PutMapping("/{expenseId}")
    public ExpenseResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                  @PathVariable UUID expenseId, @Valid @RequestBody ExpenseRequest request) {
        return expenseService.update(familyId, userId(jwt), expenseId, request);
    }

    @PostMapping("/{expenseId}/accept")
    public ExpenseResponse accept(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                  @PathVariable UUID expenseId) {
        return expenseService.accept(familyId, userId(jwt), expenseId);
    }

    @PostMapping("/{expenseId}/contest")
    public ExpenseResponse contest(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                   @PathVariable UUID expenseId, @Valid @RequestBody ContestRequest request) {
        return expenseService.contest(familyId, userId(jwt), expenseId, request);
    }

    @PostMapping("/{expenseId}/withdraw")
    public ExpenseResponse withdraw(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                    @PathVariable UUID expenseId) {
        return expenseService.withdraw(familyId, userId(jwt), expenseId);
    }

    /** Ajoute ou remplace le justificatif photo (JPEG ou PNG, nettoyé de ses métadonnées avant stockage). */
    @PutMapping(path = "/{expenseId}/receipt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ExpenseResponse putReceipt(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                      @PathVariable UUID expenseId, @RequestPart("file") MultipartFile file)
            throws IOException {
        return expenseService.putReceipt(familyId, userId(jwt), expenseId, file.getBytes());
    }

    @DeleteMapping("/{expenseId}/receipt")
    public ExpenseResponse deleteReceipt(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                         @PathVariable UUID expenseId) {
        return expenseService.deleteReceipt(familyId, userId(jwt), expenseId);
    }

    @GetMapping("/{expenseId}/receipt")
    public ResponseEntity<byte[]> receipt(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                          @PathVariable UUID expenseId) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noCache().cachePrivate())
                .body(expenseService.receipt(familyId, userId(jwt), expenseId));
    }

    @GetMapping("/balance")
    public BalanceResponse balance(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId) {
        return balanceService.balance(familyId, userId(jwt));
    }

    @PostMapping("/settlements")
    @ResponseStatus(HttpStatus.CREATED)
    public SettlementResponse declare(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                      @Valid @RequestBody SettlementRequest request) {
        return balanceService.declare(familyId, userId(jwt), request);
    }

    @PostMapping("/settlements/{settlementId}/confirm")
    public SettlementResponse confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                      @PathVariable UUID settlementId) {
        return balanceService.confirm(familyId, userId(jwt), settlementId);
    }

    @PostMapping("/settlements/{settlementId}/not-received")
    public SettlementResponse notReceived(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                          @PathVariable UUID settlementId) {
        return balanceService.notReceived(familyId, userId(jwt), settlementId);
    }

    @PostMapping("/settlements/{settlementId}/cancel")
    public SettlementResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID familyId,
                                     @PathVariable UUID settlementId) {
        return balanceService.cancel(familyId, userId(jwt), settlementId);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
