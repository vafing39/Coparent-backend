package com.vynk.coparent_backend.expense;

import com.jayway.jsonpath.JsonPath;
import com.vynk.coparent_backend.auth.email.EmailSender;
import com.vynk.coparent_backend.auth.service.RateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Dépenses partagées, solde et virements, contre la base MySQL locale. Camille est A, Julien B. */
@SpringBootTest(properties = {
        "app.auth.check-compromised-passwords=false",
        "app.auth.jwt-secret=test-only-secret-that-is-at-least-32-bytes-long"
})
@AutoConfigureMockMvc
class ExpenseIntegrationTest {

    private static final String TEST_DOMAIN = "@test.coparent.invalid";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String FAMILY = "{\"name\":\"Famille de Léa\",\"vacationZone\":\"A\"}";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Europe/Paris"));

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RateLimiter rateLimiter;
    @MockitoBean
    private EmailSender emailSender;

    private record Account(String id, String accessToken) {
    }

    private record Family(String id, String childId) {
        String expenses() {
            return "/api/v1/families/" + id + "/expenses";
        }

        String toDo() {
            return "/api/v1/families/" + id + "/to-do";
        }
    }

    @BeforeEach
    void resetRateLimits() {
        rateLimiter.reset();
    }

    @AfterEach
    void deleteTestData() {
        jdbc.update("DELETE f FROM families f JOIN family_members m ON m.family_id = f.id "
                + "JOIN users u ON u.id = m.user_id WHERE u.email LIKE ?", "%" + TEST_DOMAIN);
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + TEST_DOMAIN);
    }

    @Test
    void addedExpenseIsAnsweredByTheOtherParentThenCountsInTheBalance() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        Family family = family(camille);
        join(camille, julien, family);

        String created = call(camille, HttpMethod.POST, family.expenses(),
                expense("  Licence de judo  ", 8500, "PARENT_A", 50, family.childId()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.label").value("Licence de judo"))
                .andExpect(jsonPath("$.owedCents").value(4250))
                .andExpect(jsonPath("$.children[0].firstName").value("Léa"))
                .andExpect(jsonPath("$.authorFirstName").value("Camille"))
                .andExpect(jsonPath("$.mine").value(true))
                .andExpect(jsonPath("$.canAnswer").value(false))
                .andExpect(jsonPath("$.canEdit").value(true))
                .andReturn().getResponse().getContentAsString();
        String expenseId = JsonPath.read(created, "$.id");

        call(julien, HttpMethod.GET, family.toDo(), null)
                .andExpect(jsonPath("$.mine", hasSize(1)))
                .andExpect(jsonPath("$.mine[0].kind").value("EXPENSE"))
                .andExpect(jsonPath("$.mine[0].targetId").value(expenseId))
                .andExpect(jsonPath("$.mine[0].subject").value("Dépense : Licence de judo"));
        call(julien, HttpMethod.GET, family.expenses(), null)
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].canAnswer").value(true))
                .andExpect(jsonPath("$[0].canEdit").value(false));

        // Pas encore validée : hors du solde
        call(camille, HttpMethod.GET, family.expenses() + "/balance", null)
                .andExpect(jsonPath("$.debtorRole", nullValue()))
                .andExpect(jsonPath("$.amountCents").value(0))
                .andExpect(jsonPath("$.lines", hasSize(0)));

        call(camille, HttpMethod.POST, family.expenses() + "/" + expenseId + "/accept", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Vous ne pouvez pas répondre à votre propre dépense."));
        call(julien, HttpMethod.POST, family.expenses() + "/" + expenseId + "/accept", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"));
        call(julien, HttpMethod.GET, family.toDo(), null).andExpect(jsonPath("$.mine", hasSize(0)));
        call(camille, HttpMethod.POST, family.expenses() + "/" + expenseId + "/withdraw", null)
                .andExpect(status().isConflict());

        // Payée par Julien, 70/30 : Camille lui doit 30 % de 10,05 €, soit 3,02 €
        String second = call(julien, HttpMethod.POST, family.expenses(),
                expense("Pharmacie", 1005, "PARENT_B", 70, family.childId()))
                .andExpect(jsonPath("$.owedCents").value(302))
                .andReturn().getResponse().getContentAsString();
        call(camille, HttpMethod.POST, family.expenses() + "/" + JsonPath.read(second, "$.id") + "/accept", null)
                .andExpect(status().isOk());

        for (Account parent : new Account[]{camille, julien}) {
            call(parent, HttpMethod.GET, family.expenses() + "/balance", null)
                    .andExpect(jsonPath("$.debtorRole").value("PARENT_B"))
                    .andExpect(jsonPath("$.amountCents").value(4250 - 302))
                    .andExpect(jsonPath("$.lines", hasSize(2)))
                    .andExpect(jsonPath("$.openSettlement", nullValue()));
        }
    }

    @Test
    void contestedExpenseGoesBackToTheAuthorWhoCorrectsOrWithdrawsIt() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        Family family = family(camille);
        join(camille, julien, family);

        String id = JsonPath.read(call(julien, HttpMethod.POST, family.expenses(),
                        expense("Cantine de novembre", 6420, "PARENT_B", 50, family.childId()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        String path = family.expenses() + "/" + id;

        call(camille, HttpMethod.POST, path + "/contest",
                "{\"reason\":\"AMOUNT\",\"note\":\"  La facture indique 21 repas.  \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONTESTED"))
                .andExpect(jsonPath("$.contestReason").value("AMOUNT"))
                .andExpect(jsonPath("$.contestNote").value("La facture indique 21 repas."));
        call(camille, HttpMethod.GET, family.toDo(), null).andExpect(jsonPath("$.mine", hasSize(0)));
        call(julien, HttpMethod.GET, family.toDo(), null)
                .andExpect(jsonPath("$.mine", hasSize(1)))
                .andExpect(jsonPath("$.mine[0].subject").value("Dépense contestée : Cantine de novembre"));
        call(camille, HttpMethod.POST, path + "/accept", null).andExpect(status().isConflict());
        call(camille, HttpMethod.PUT, path, expense("Cantine", 5400, "PARENT_B", 50, family.childId()))
                .andExpect(status().isConflict());

        // Julien corrige : elle repasse en attente, chez Camille
        call(julien, HttpMethod.PUT, path, expense("Cantine de novembre", 5400, "PARENT_B", 50, family.childId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amountCents").value(5400))
                .andExpect(jsonPath("$.contestReason", nullValue()));
        call(julien, HttpMethod.GET, family.toDo(), null).andExpect(jsonPath("$.mine", hasSize(0)));
        call(camille, HttpMethod.GET, family.toDo(), null).andExpect(jsonPath("$.mine", hasSize(1)));
        call(camille, HttpMethod.POST, path + "/accept", null)
                .andExpect(jsonPath("$.status").value("VALIDATED"));
        call(camille, HttpMethod.GET, family.expenses() + "/balance", null)
                .andExpect(jsonPath("$.debtorRole").value("PARENT_A"))
                .andExpect(jsonPath("$.amountCents").value(2700));

        // Une autre, contestée puis retirée : elle disparaît de la liste et de « À traiter »
        String other = JsonPath.read(call(julien, HttpMethod.POST, family.expenses(),
                        expense("Lunettes", 12900, "PARENT_B", 50, family.childId()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        call(camille, HttpMethod.POST, family.expenses() + "/" + other + "/contest", "{\"reason\":\"NOT_AGREED\"}")
                .andExpect(status().isOk());
        call(camille, HttpMethod.POST, family.expenses() + "/" + other + "/withdraw", null)
                .andExpect(status().isConflict());
        call(julien, HttpMethod.POST, family.expenses() + "/" + other + "/withdraw", null)
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
        call(julien, HttpMethod.GET, family.toDo(), null).andExpect(jsonPath("$.mine", hasSize(0)));
        call(camille, HttpMethod.GET, family.expenses(), null).andExpect(jsonPath("$", hasSize(1)));
        call(camille, HttpMethod.GET, family.expenses() + "/" + other, null).andExpect(status().isNotFound());
    }

    @Test
    void debtorDeclaresATransferThatTheOtherParentConfirms() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        Family family = family(camille);
        join(camille, julien, family);
        String id = JsonPath.read(call(camille, HttpMethod.POST, family.expenses(),
                        expense("Licence de judo", 8500, "PARENT_A", 50, family.childId()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        call(julien, HttpMethod.POST, family.expenses() + "/" + id + "/accept", null).andExpect(status().isOk());
        String settlements = family.expenses() + "/settlements";

        call(camille, HttpMethod.POST, settlements, "{\"amountCents\":4250}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Vous ne devez rien à Julien."));
        call(julien, HttpMethod.POST, settlements, "{\"amountCents\":5000}")
                .andExpect(status().isBadRequest());

        String declared = call(julien, HttpMethod.POST, settlements, "{\"amountCents\":4250}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DECLARED"))
                .andExpect(jsonPath("$.mine").value(true))
                .andReturn().getResponse().getContentAsString();
        String settlementId = JsonPath.read(declared, "$.id");
        call(julien, HttpMethod.POST, settlements, "{\"amountCents\":100}").andExpect(status().isConflict());
        call(camille, HttpMethod.GET, family.toDo(), null)
                .andExpect(jsonPath("$.mine", hasSize(1)))
                .andExpect(jsonPath("$.mine[0].kind").value("SETTLEMENT"))
                .andExpect(jsonPath("$.mine[0].subject").value("Virement de 42,50 € à confirmer"));
        call(camille, HttpMethod.GET, family.expenses() + "/balance", null)
                .andExpect(jsonPath("$.amountCents").value(4250))
                .andExpect(jsonPath("$.openSettlement.id").value(settlementId))
                .andExpect(jsonPath("$.openSettlement.mine").value(false));

        call(julien, HttpMethod.POST, settlements + "/" + settlementId + "/confirm", null)
                .andExpect(status().isConflict());
        call(camille, HttpMethod.POST, settlements + "/" + settlementId + "/not-received", null)
                .andExpect(jsonPath("$.status").value("NOT_RECEIVED"));
        call(camille, HttpMethod.GET, family.toDo(), null).andExpect(jsonPath("$.mine", hasSize(1)));
        call(camille, HttpMethod.POST, settlements + "/" + settlementId + "/confirm", null)
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        call(camille, HttpMethod.GET, family.toDo(), null).andExpect(jsonPath("$.mine", hasSize(0)));

        call(julien, HttpMethod.GET, family.expenses() + "/balance", null)
                .andExpect(jsonPath("$.debtorRole", nullValue()))
                .andExpect(jsonPath("$.amountCents").value(0))
                .andExpect(jsonPath("$.lines", hasSize(2)))
                .andExpect(jsonPath("$.openSettlement", nullValue()));
    }

    @Test
    void soloExpensesAreProposedToTheOtherParentWhenTheyJoin() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        Family family = family(camille);

        call(camille, HttpMethod.POST, family.expenses(), expense("Pharmacie", 560, "PARENT_A", 50, family.childId()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.canAnswer").value(false));
        call(camille, HttpMethod.POST, family.expenses() + "/settlements", "{\"amountCents\":100}")
                .andExpect(status().isConflict());

        join(camille, julien, family);
        call(julien, HttpMethod.GET, family.toDo(), null)
                .andExpect(jsonPath("$.mine", hasSize(1)))
                .andExpect(jsonPath("$.mine[0].kind").value("EXPENSE"))
                .andExpect(jsonPath("$.mine[0].requestedBy").value("Camille"));
        call(julien, HttpMethod.GET, family.expenses(), null)
                .andExpect(jsonPath("$[0].canAnswer").value(true));
    }

    @Test
    void invalidExpensesAreRefused() throws Exception {
        Account camille = verifiedUser("Camille");
        Account stranger = verifiedUser("Paul");
        Family family = family(camille);
        Family otherFamily = family(stranger);

        call(camille, HttpMethod.POST, family.expenses(), expense("Rien", 0, "PARENT_A", 50, family.childId()))
                .andExpect(status().isBadRequest());
        call(camille, HttpMethod.POST, family.expenses(),
                        expense("Trop", 10_000_001, "PARENT_A", 50, family.childId()))
                .andExpect(status().isBadRequest());
        call(camille, HttpMethod.POST, family.expenses(), expense("45 %", 1000, "PARENT_A", 45, family.childId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Choisissez une répartition proposée."));
        call(camille, HttpMethod.POST, family.expenses(),
                        expense("Autre enfant", 1000, "PARENT_A", 50, otherFamily.childId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Choisissez les enfants de la famille."));
        call(camille, HttpMethod.POST, family.expenses(), expense(" ", 1000, "PARENT_A", 50, family.childId()))
                .andExpect(status().isBadRequest());
        call(camille, HttpMethod.POST, family.expenses(),
                        expense("Demain", 1000, "PARENT_A", 50, TODAY.plusDays(1), List.of(family.childId())))
                .andExpect(status().isBadRequest());
        call(camille, HttpMethod.POST, family.expenses(),
                        expense("Sans enfant", 1000, "PARENT_A", 50, TODAY, List.of()))
                .andExpect(status().isBadRequest());
        call(stranger, HttpMethod.GET, family.expenses(), null).andExpect(status().isNotFound());
    }

    private static String expense(String label, int amountCents, String payerRole, int payerShare, String childId) {
        return expense(label, amountCents, payerRole, payerShare, TODAY, List.of(childId));
    }

    private static String expense(String label, int amountCents, String payerRole, int payerShare, LocalDate paidOn,
                                  List<String> childIds) {
        String children = String.join(",", childIds.stream().map(id -> "\"" + id + "\"").toList());
        return "{\"label\":\"" + label + "\",\"amountCents\":" + amountCents + ",\"category\":\"HEALTH\","
                + "\"payerRole\":\"" + payerRole + "\",\"paidOn\":\"" + paidOn + "\",\"payerSharePercent\":"
                + payerShare + ",\"childIds\":[" + children + "]}";
    }

    private Account verifiedUser(String firstName) throws Exception {
        String email = "user-" + UUID.randomUUID() + TEST_DOMAIN;
        String body = mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"confirmPassword\":\""
                                + PASSWORD + "\",\"firstName\":\"" + firstName + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        jdbc.update("UPDATE users SET email_verified_at = UTC_TIMESTAMP(6) WHERE email = ?", email);
        return new Account(JsonPath.read(body, "$.user.id"), JsonPath.read(body, "$.accessToken"));
    }

    /** Famille de l'utilisateur, avec un enfant, Léa. */
    private Family family(Account account) throws Exception {
        String body = call(account, HttpMethod.POST, "/api/v1/families", FAMILY)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String familyId = JsonPath.read(body, "$.id");
        String child = call(account, HttpMethod.POST, "/api/v1/families/" + familyId + "/children",
                "{\"firstName\":\"Léa\",\"birthDate\":\"2018-03-12\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Family(familyId, JsonPath.read(child, "$.id"));
    }

    private void join(Account inviter, Account invitee, Family family) throws Exception {
        String invitation = call(inviter, HttpMethod.POST, "/api/v1/families/" + family.id() + "/invitations",
                "{\"inviteeFirstName\":\"Julien\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String link = JsonPath.read(invitation, "$.link");
        String token = link.substring(link.indexOf("token=") + "token=".length());
        call(invitee, HttpMethod.POST, "/api/v1/invitations/" + token + "/accept", null).andExpect(status().isOk());
    }

    private ResultActions call(Account account, HttpMethod method, String path, String json) throws Exception {
        var builder = request(method, path).header("Authorization", "Bearer " + account.accessToken());
        if (json != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(builder);
    }
}
