package com.vynk.coparent_backend.message;

import com.jayway.jsonpath.JsonPath;
import com.vynk.coparent_backend.auth.email.EmailSender;
import com.vynk.coparent_backend.auth.service.RateLimiter;
import com.vynk.coparent_backend.message.repository.MessageRepository;
import com.vynk.coparent_backend.message.service.MessageHasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;


import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /families/{id}/messages against the local MySQL database: order, pages, reads, immutability, hash chain. */
@SpringBootTest(properties = {
        "app.auth.check-compromised-passwords=false",
        "app.auth.jwt-secret=test-only-secret-that-is-at-least-32-bytes-long"
})
@AutoConfigureMockMvc
class MessageIntegrationTest {

    private static final String TEST_DOMAIN = "@test.coparent.invalid";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String FAMILY = "{\"name\":\"Famille de Léa et Hugo\",\"vacationZone\":\"A\"}";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RateLimiter rateLimiter;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private MessageHasher hasher;
    @Autowired
    private TransactionTemplate transaction;
    @MockitoBean
    private EmailSender emailSender;

    @BeforeEach
    void resetRateLimits() {
        rateLimiter.reset();
    }

    @AfterEach
    void deleteTestData() {
        // Les messages partent avec la famille : les cascades ne déclenchent pas les triggers.
        jdbc.update("DELETE f FROM families f JOIN family_members m ON m.family_id = f.id "
                + "JOIN users u ON u.id = m.user_id WHERE u.email LIKE ?", "%" + TEST_DOMAIN);
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + TEST_DOMAIN);
    }

    @Test
    void conversationBetweenTheTwoParents() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        String familyId = familyOf(camille);
        String messages = "/api/v1/families/" + familyId + "/messages";

        // Seul : la messagerie n'est pas encore ouverte
        call(camille, HttpMethod.POST, messages, send("Bonjour")).andExpect(status().isConflict());
        join(camille, julien, familyId);

        call(camille, HttpMethod.POST, messages, send("  Hugo a oublié son kimono.  "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seq").value(1))
                .andExpect(jsonPath("$.body").value("Hugo a oublié son kimono."))
                .andExpect(jsonPath("$.senderId").value(camille.id()))
                .andExpect(jsonPath("$.senderFirstName").value("Camille"))
                .andExpect(jsonPath("$.createdAt", notNullValue()))
                .andExpect(jsonPath("$.readAt", nullValue()));
        call(julien, HttpMethod.POST, messages, send("Merci, je préviens la maîtresse."))
                .andExpect(jsonPath("$.seq").value(2));
        call(camille, HttpMethod.POST, messages, send("Parfait.")).andExpect(jsonPath("$.seq").value(3));

        call(julien, messages)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages", hasSize(3)))
                .andExpect(jsonPath("$.messages[0].body").value("Hugo a oublié son kimono."))
                .andExpect(jsonPath("$.messages[2].body").value("Parfait."))
                .andExpect(jsonPath("$.hasMore").value(false));

        // Pages : les plus récents d'abord, puis ceux d'avant
        call(julien, messages + "?limit=2")
                .andExpect(jsonPath("$.messages[*].seq", contains(2, 3)))
                .andExpect(jsonPath("$.hasMore").value(true));
        call(julien, messages + "?limit=2&before=2")
                .andExpect(jsonPath("$.messages[*].seq", contains(1)))
                .andExpect(jsonPath("$.hasMore").value(false));

        // Julien lit jusqu'au 3 : seuls les messages de Camille deviennent « lus »
        call(julien, HttpMethod.POST, messages + "/read", "{\"upToSeq\":3}").andExpect(status().isNoContent());
        call(camille, messages)
                .andExpect(jsonPath("$.messages[0].readAt", notNullValue()))
                .andExpect(jsonPath("$.messages[1].readAt", nullValue()))
                .andExpect(jsonPath("$.messages[2].readAt", notNullValue()));
        // Relire ne change pas l'heure de lecture
        String readAt = JsonPath.read(call(camille, messages).andReturn().getResponse().getContentAsString(),
                "$.messages[0].readAt");
        call(julien, HttpMethod.POST, messages + "/read", "{\"upToSeq\":3}").andExpect(status().isNoContent());
        call(camille, messages).andExpect(jsonPath("$.messages[0].readAt").value(readAt));

        // Hash chaîné intact
        UUID family = UUID.fromString(familyId);
        Boolean intact = transaction.execute(tx -> hasher.verify(messageRepository.findByFamilyIdOrderBySeqAsc(family)));
        assertThat(intact).isTrue();
    }

    @Test
    void messagesCannotBeChangedOrDeletedEvenInTheDatabase() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        String familyId = familyOf(camille);
        join(camille, julien, familyId);
        call(camille, HttpMethod.POST, "/api/v1/families/" + familyId + "/messages", send("Texte d'origine"))
                .andExpect(status().isCreated());

        assertThatThrownBy(() -> jdbc.update("UPDATE messages SET body = 'Texte changé' WHERE family_id = UUID_TO_BIN(?)",
                familyId)).isInstanceOf(DataAccessException.class).hasMessageContaining("ne peut pas être modifié");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM messages WHERE family_id = UUID_TO_BIN(?)", familyId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ne peut pas être supprimé");
        // Aucune route de modification ou de suppression
        call(camille, HttpMethod.PUT, "/api/v1/families/" + familyId + "/messages", send("x"))
                .andExpect(status().is4xxClientError());
        call(camille, HttpMethod.DELETE, "/api/v1/families/" + familyId + "/messages", null)
                .andExpect(status().is4xxClientError());

        // Une modification faite malgré tout (triggers retirés) se voit dans la chaîne
        UUID family = UUID.fromString(familyId);
        transaction.executeWithoutResult(tx -> {
            var stored = messageRepository.findByFamilyIdOrderBySeqAsc(family);
            assertThat(hasher.verify(stored)).isTrue();
            assertThat(hasher.hash(family, 1, UUID.fromString(camille.id()), stored.getFirst().getCreatedAt(), null,
                    "Texte changé")).isNotEqualTo(stored.getFirst().getHash());
        });
    }

    @Test
    void errors() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        Account stranger = verifiedUser("Inconnu");
        String familyId = familyOf(camille);
        join(camille, julien, familyId);
        String messages = "/api/v1/families/" + familyId + "/messages";

        call(camille, HttpMethod.POST, messages, send("   ")).andExpect(status().isBadRequest());
        call(camille, HttpMethod.POST, messages, send("a".repeat(2001))).andExpect(status().isBadRequest());
        call(camille, HttpMethod.POST, messages, send("a".repeat(2000))).andExpect(status().isCreated());
        call(camille, HttpMethod.POST, messages + "/read", "{\"upToSeq\":0}").andExpect(status().isBadRequest());
        call(stranger, messages).andExpect(status().isNotFound());
        call(stranger, HttpMethod.POST, messages, send("Bonjour")).andExpect(status().isNotFound());
        call(stranger, HttpMethod.POST, messages + "/read", "{\"upToSeq\":1}").andExpect(status().isNotFound());
    }

    private static String send(String body) {
        return "{\"body\":\"" + body + "\"}";
    }

    // --- helpers ---

    private record Account(String id, String accessToken) {
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

    private String familyOf(Account account) throws Exception {
        String body = call(account, HttpMethod.POST, "/api/v1/families", FAMILY)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /** The other parent joins through an invitation. */
    private void join(Account inviter, Account invitee, String familyId) throws Exception {
        String invitation = call(inviter, HttpMethod.POST, "/api/v1/families/" + familyId + "/invitations",
                "{\"inviteeFirstName\":\"Julien\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String link = JsonPath.read(invitation, "$.link");
        String token = link.substring(link.indexOf("token=") + "token=".length());
        call(invitee, HttpMethod.POST, "/api/v1/invitations/" + token + "/accept", null).andExpect(status().isOk());
    }

    private ResultActions call(Account account, String path) throws Exception {
        return call(account, HttpMethod.GET, path, null);
    }

    private ResultActions call(Account account, HttpMethod method, String path, String json) throws Exception {
        var builder = request(method, path).header("Authorization", "Bearer " + account.accessToken());
        if (json != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(builder);
    }
}
