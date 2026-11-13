package com.vynk.coparent_backend.family;

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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Validation du rythme par l'autre parent (spec §3), contre la base MySQL locale. */
@SpringBootTest(properties = {
        "app.auth.check-compromised-passwords=false",
        "app.auth.jwt-secret=test-only-secret-that-is-at-least-32-bytes-long"
})
@AutoConfigureMockMvc
class ScheduleValidationIntegrationTest {

    private static final String TEST_DOMAIN = "@test.coparent.invalid";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String FAMILY = "{\"name\":\"Famille de Léa et Hugo\",\"vacationZone\":\"A\"}";
    private static final String WEEKS = "{\"pattern\":\"ALTERNATE_WEEKS\",\"startDate\":\"2026-11-16\","
            + "\"handoverTime\":\"17:50\",\"startingParent\":\"PARENT_A\"}";
    private static final String TWO_TWO_THREE = "{\"pattern\":\"TWO_TWO_THREE\",\"startDate\":\"2026-11-16\","
            + "\"handoverTime\":\"18:00\",\"startingParent\":\"PARENT_B\"}";

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
    void joiningAsksTheNewParentThenValidationActivatesTheRhythm() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        String familyId = family(camille);
        String scheduleId = propose(camille, familyId, WEEKS);
        join(camille, julien, familyId);
        String base = "/api/v1/families/" + familyId;

        call(julien, HttpMethod.GET, base + "/to-do", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mine", hasSize(1)))
                .andExpect(jsonPath("$.mine[0].kind").value("CUSTODY_SCHEDULE"))
                .andExpect(jsonPath("$.mine[0].targetId").value(scheduleId))
                .andExpect(jsonPath("$.mine[0].subject").value("Rythme : une semaine sur deux"));
        call(julien, HttpMethod.GET, base + "/custody-schedules/" + scheduleId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.proposedById").value(camille.id()))
                .andExpect(jsonPath("$.proposedByFirstName").value("Camille"));

        // Celui qui a proposé ne valide pas lui-même.
        call(camille, HttpMethod.POST, base + "/custody-schedules/" + scheduleId + "/accept", null)
                .andExpect(status().isConflict());

        call(julien, HttpMethod.POST, base + "/custody-schedules/" + scheduleId + "/accept", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        call(julien, HttpMethod.GET, base + "/to-do", null)
                .andExpect(jsonPath("$.mine", hasSize(0)));
        call(camille, HttpMethod.GET, base + "/to-do", null)
                .andExpect(jsonPath("$.theirs", hasSize(0)));
        call(camille, HttpMethod.GET, "/api/v1/families/me", null)
                .andExpect(jsonPath("$.custodySchedule.status").value("ACTIVE"));

        // Déjà validé.
        call(julien, HttpMethod.POST, base + "/custody-schedules/" + scheduleId + "/accept", null)
                .andExpect(status().isConflict());
    }

    @Test
    void counterProposalReplacesTheReceivedOneAndAsksTheOtherParent() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        String familyId = family(camille);
        String received = propose(camille, familyId, WEEKS);
        join(camille, julien, familyId);
        String base = "/api/v1/families/" + familyId;

        String counter = propose(julien, familyId, TWO_TWO_THREE);

        assertThat(scheduleStatus(received)).isEqualTo("REPLACED");
        call(julien, HttpMethod.GET, base + "/to-do", null)
                .andExpect(jsonPath("$.mine", hasSize(0)))
                .andExpect(jsonPath("$.theirs", hasSize(1)));
        call(camille, HttpMethod.GET, base + "/to-do", null)
                .andExpect(jsonPath("$.mine", hasSize(1)))
                .andExpect(jsonPath("$.mine[0].targetId").value(counter))
                .andExpect(jsonPath("$.mine[0].subject").value("Rythme : 2-2-3"));
        call(camille, HttpMethod.POST, base + "/custody-schedules/" + received + "/accept", null)
                .andExpect(status().isConflict());

        call(camille, HttpMethod.POST, base + "/custody-schedules/" + counter + "/accept", null)
                .andExpect(status().isOk());

        // Un nouveau rythme validé remplace l'ancien.
        String next = propose(camille, familyId, WEEKS);
        call(julien, HttpMethod.POST, base + "/custody-schedules/" + next + "/accept", null)
                .andExpect(status().isOk());
        assertThat(scheduleStatus(counter)).isEqualTo("REPLACED");
        assertThat(scheduleStatus(next)).isEqualTo("ACTIVE");
    }

    @Test
    void soloProposalAsksNobodyAndCannotBeValidated() throws Exception {
        Account camille = verifiedUser("Camille");
        Account stranger = verifiedUser("Inconnu");
        String familyId = family(camille);
        String scheduleId = propose(camille, familyId, WEEKS);
        String base = "/api/v1/families/" + familyId;

        call(camille, HttpMethod.GET, base + "/to-do", null)
                .andExpect(jsonPath("$.mine", hasSize(0)))
                .andExpect(jsonPath("$.theirs", hasSize(0)));
        call(camille, HttpMethod.POST, base + "/custody-schedules/" + scheduleId + "/accept", null)
                .andExpect(status().isConflict());
        call(stranger, HttpMethod.GET, base + "/custody-schedules/" + scheduleId, null)
                .andExpect(status().isNotFound());
    }

    private String scheduleStatus(String scheduleId) {
        return jdbc.queryForObject("SELECT status FROM custody_schedules WHERE id = UUID_TO_BIN(?)",
                String.class, scheduleId);
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

    private String family(Account account) throws Exception {
        String body = call(account, HttpMethod.POST, "/api/v1/families", FAMILY)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String propose(Account account, String familyId, String json) throws Exception {
        String body = call(account, HttpMethod.PUT, "/api/v1/families/" + familyId + "/custody-schedule", json)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private void join(Account inviter, Account invitee, String familyId) throws Exception {
        String invitation = call(inviter, HttpMethod.POST, "/api/v1/families/" + familyId + "/invitations",
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
