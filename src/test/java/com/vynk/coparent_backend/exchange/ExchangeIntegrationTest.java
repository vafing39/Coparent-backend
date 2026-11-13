package com.vynk.coparent_backend.exchange;

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
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Demandes d'échange de garde contre la base MySQL locale. Une semaine sur deux à partir de S (dans 7 jours) :
 * Camille (A) a les nuits S..S+6, Julien (B) S+7..S+13, Camille S+14..S+20. Sans règle de vacances, le rythme
 * habituel continue pendant les vacances : les dates restent valables quel que soit le jour du test.
 */
@SpringBootTest(properties = {
        "app.auth.check-compromised-passwords=false",
        "app.auth.jwt-secret=test-only-secret-that-is-at-least-32-bytes-long"
})
@AutoConfigureMockMvc
class ExchangeIntegrationTest {

    private static final String TEST_DOMAIN = "@test.coparent.invalid";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String FAMILY = "{\"name\":\"Famille de Léa et Hugo\",\"vacationZone\":\"A\"}";
    private static final LocalDate S = LocalDate.now(ZoneId.of("Europe/Paris")).plusDays(7);

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
    void requestShowsInToDoThenAcceptRecomputesCustodyForBoth() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        String familyId = familyWithSchedule(camille);
        join(camille, julien, familyId);
        String base = "/api/v1/families/" + familyId + "/exchanges";

        String created = call(camille, HttpMethod.POST, base,
                body(S.plusDays(7), S.plusDays(9), S.plusDays(14), S.plusDays(16), "  Mariage de ma sœur  "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.requesterRole").value("PARENT_A"))
                .andExpect(jsonPath("$.requestedByFirstName").value("Camille"))
                .andExpect(jsonPath("$.mine").value(true))
                .andExpect(jsonPath("$.takeFrom").value(S.plusDays(7).toString()))
                .andExpect(jsonPath("$.giveTo").value(S.plusDays(16).toString()))
                .andExpect(jsonPath("$.message").value("Mariage de ma sœur"))
                .andReturn().getResponse().getContentAsString();
        String exchangeId = JsonPath.read(created, "$.id");

        // « À traiter » : à Julien de répondre, en attente pour Camille
        call(julien, HttpMethod.GET, "/api/v1/families/" + familyId + "/to-do", null)
                .andExpect(jsonPath("$.mine", hasSize(1)))
                .andExpect(jsonPath("$.mine[0].kind").value("EXCHANGE"))
                .andExpect(jsonPath("$.mine[0].targetId").value(exchangeId))
                .andExpect(jsonPath("$.mine[0].subject", startsWith("Échange : du ")))
                .andExpect(jsonPath("$.mine[0].requestedBy").value("Camille"));
        call(camille, HttpMethod.GET, "/api/v1/families/" + familyId + "/to-do", null)
                .andExpect(jsonPath("$.theirs", hasSize(1)));

        call(julien, HttpMethod.GET, base + "/" + exchangeId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mine").value(false));
        call(julien, HttpMethod.GET, base, null)
                .andExpect(jsonPath("$", hasSize(1)));

        // Pas encore acceptée : la garde ne change pas
        String custody = "/api/v1/families/" + familyId + "/custody?from=" + S.plusDays(7) + "&to=" + S.plusDays(17);
        call(camille, HttpMethod.GET, custody, null)
                .andExpect(jsonPath("$.nights[0].parent").value("PARENT_B"))
                .andExpect(jsonPath("$.nights[0].period").value("REGULAR"));

        // Camille ne répond pas à sa propre demande ; Julien ne l'annule pas
        call(camille, HttpMethod.POST, base + "/" + exchangeId + "/accept", null).andExpect(status().isConflict());
        call(julien, HttpMethod.POST, base + "/" + exchangeId + "/cancel", null).andExpect(status().isConflict());

        call(julien, HttpMethod.POST, base + "/" + exchangeId + "/accept", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.mine").value(false));
        call(julien, HttpMethod.POST, base + "/" + exchangeId + "/decline", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Cette demande n'attend plus de réponse."));

        // La ligne quitte « À traiter »
        call(julien, HttpMethod.GET, "/api/v1/families/" + familyId + "/to-do", null)
                .andExpect(jsonPath("$.mine", hasSize(0)));

        // Garde recalculée, pour les deux parents. Nuits S+7..S+17 :
        // A A A (échange) B B B B (S+10..S+13) B B B (échange, S+14..S+16) A (S+17)
        for (Account parent : new Account[]{camille, julien}) {
            call(parent, HttpMethod.GET, custody, null)
                    .andExpect(jsonPath("$.nights[0].parent").value("PARENT_A"))
                    .andExpect(jsonPath("$.nights[0].period").value("EXCHANGE"))
                    .andExpect(jsonPath("$.nights[2].parent").value("PARENT_A"))
                    .andExpect(jsonPath("$.nights[3].parent").value("PARENT_B"))
                    .andExpect(jsonPath("$.nights[3].period").value("REGULAR"))
                    .andExpect(jsonPath("$.nights[7].parent").value("PARENT_B"))
                    .andExpect(jsonPath("$.nights[7].period").value("EXCHANGE"))
                    .andExpect(jsonPath("$.nights[10].parent").value("PARENT_A"))
                    .andExpect(jsonPath("$.handovers", hasSize(2)))
                    .andExpect(jsonPath("$.handovers[0].at").value(S.plusDays(10) + "T17:50:00"))
                    .andExpect(jsonPath("$.handovers[0].to").value("PARENT_B"))
                    .andExpect(jsonPath("$.handovers[1].at").value(S.plusDays(17) + "T17:50:00"))
                    .andExpect(jsonPath("$.handovers[1].to").value("PARENT_A"));
        }
    }

    @Test
    void declineAndCancelLeaveCustodyUnchanged() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        String familyId = familyWithSchedule(camille);
        join(camille, julien, familyId);
        String base = "/api/v1/families/" + familyId + "/exchanges";

        String first = JsonPath.read(call(julien, HttpMethod.POST, base, body(S, S, null, null, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.giveFrom").value(nullValue()))
                .andExpect(jsonPath("$.message").value(nullValue()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        call(camille, HttpMethod.POST, base + "/" + first + "/decline", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"));

        String second = JsonPath.read(call(julien, HttpMethod.POST, base, body(S, S, null, null, null))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        call(julien, HttpMethod.POST, base + "/" + second + "/cancel", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        call(camille, HttpMethod.GET, "/api/v1/families/" + familyId + "/to-do", null)
                .andExpect(jsonPath("$.mine", hasSize(0)));
        call(camille, HttpMethod.GET, base, null).andExpect(jsonPath("$", hasSize(0)));
        call(camille, HttpMethod.GET, "/api/v1/families/" + familyId + "/custody?from=" + S + "&to=" + S, null)
                .andExpect(jsonPath("$.nights[0].parent").value("PARENT_A"))
                .andExpect(jsonPath("$.nights[0].period").value("REGULAR"));
    }

    @Test
    void validation() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        Account stranger = verifiedUser("Inconnu");
        String familyId = familyWithSchedule(camille);
        String base = "/api/v1/families/" + familyId + "/exchanges";

        // Seule dans la famille
        call(camille, HttpMethod.POST, base, body(S.plusDays(7), S.plusDays(7), null, null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("L'échange de garde s'ouvrira quand l'autre parent aura rejoint."));
        join(camille, julien, familyId);

        // Nuits qui ne sont pas chez l'autre parent, ou pas chez moi
        call(camille, HttpMethod.POST, base, body(S, S.plusDays(1), null, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Les nuits que vous prenez doivent être chez Julien."));
        call(camille, HttpMethod.POST, base, body(S.plusDays(7), S.plusDays(8), S.plusDays(9), S.plusDays(9), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Les nuits que vous laissez doivent être chez vous."));
        // Dates
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Paris"));
        call(camille, HttpMethod.POST, base, body(today, today, null, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Un échange porte sur des nuits à venir, à partir de demain."));
        call(camille, HttpMethod.POST, base, body(S.plusDays(9), S.plusDays(7), null, null, null))
                .andExpect(status().isBadRequest());
        call(camille, HttpMethod.POST, base, body(S.plusDays(7), S.plusDays(38), null, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("31 nuits au plus par période."));
        call(camille, HttpMethod.POST, base, body(S.plusDays(7), S.plusDays(8), S.plusDays(14), null, null))
                .andExpect(status().isBadRequest());
        call(camille, HttpMethod.POST, base, body(S.plusDays(7), S.plusDays(8), S.plusDays(8), S.plusDays(9), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Les deux périodes ne peuvent pas se chevaucher."));
        call(camille, HttpMethod.POST, base, "{\"takeFrom\":\"" + S.plusDays(7) + "\"}")
                .andExpect(status().isBadRequest());
        call(camille, HttpMethod.POST, base,
                body(S.plusDays(7), S.plusDays(7), null, null, "x".repeat(501)))
                .andExpect(status().isBadRequest());

        // Nuits déjà demandées
        call(camille, HttpMethod.POST, base, body(S.plusDays(7), S.plusDays(8), null, null, null))
                .andExpect(status().isCreated());
        call(camille, HttpMethod.POST, base, body(S.plusDays(8), S.plusDays(9), null, null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Certaines de ces nuits font déjà l'objet d'un échange."));

        // Autre famille
        call(stranger, HttpMethod.GET, base, null).andExpect(status().isNotFound());
        call(camille, HttpMethod.GET, base + "/" + UUID.randomUUID(), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Échange introuvable."));
    }

    // --- helpers ---

    private static String body(LocalDate takeFrom, LocalDate takeTo, LocalDate giveFrom, LocalDate giveTo,
                               String message) {
        return "{\"takeFrom\":\"" + takeFrom + "\",\"takeTo\":\"" + takeTo + "\""
                + (giveFrom == null ? "" : ",\"giveFrom\":\"" + giveFrom + "\"")
                + (giveTo == null ? "" : ",\"giveTo\":\"" + giveTo + "\"")
                + (message == null ? "" : ",\"message\":\"" + message + "\"") + "}";
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

    /** Famille de Camille (A), une semaine sur deux à partir de S, sans règle de vacances. */
    private String familyWithSchedule(Account account) throws Exception {
        String body = call(account, HttpMethod.POST, "/api/v1/families", FAMILY)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String familyId = JsonPath.read(body, "$.id");
        call(account, HttpMethod.PUT, "/api/v1/families/" + familyId + "/custody-schedule",
                "{\"pattern\":\"ALTERNATE_WEEKS\",\"startDate\":\"" + S
                        + "\",\"handoverTime\":\"17:50\",\"startingParent\":\"PARENT_A\"}")
                .andExpect(status().isOk());
        return familyId;
    }

    /** L'autre parent rejoint par une invitation et valide le rythme proposé. */
    private void join(Account inviter, Account invitee, String familyId) throws Exception {
        String invitation = call(inviter, HttpMethod.POST, "/api/v1/families/" + familyId + "/invitations",
                "{\"inviteeFirstName\":\"Julien\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String link = JsonPath.read(invitation, "$.link");
        String token = link.substring(link.indexOf("token=") + "token=".length());
        call(invitee, HttpMethod.POST, "/api/v1/invitations/" + token + "/accept", null).andExpect(status().isOk());
        String toDo = call(invitee, HttpMethod.GET, "/api/v1/families/" + familyId + "/to-do", null)
                .andReturn().getResponse().getContentAsString();
        String scheduleId = JsonPath.read(toDo, "$.mine[0].targetId");
        call(invitee, HttpMethod.POST, "/api/v1/families/" + familyId + "/custody-schedules/" + scheduleId + "/accept",
                null).andExpect(status().isOk());
    }

    private ResultActions call(Account account, HttpMethod method, String path, String json) throws Exception {
        var builder = request(method, path).header("Authorization", "Bearer " + account.accessToken());
        if (json != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(builder);
    }
}
