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
    void discussionsBySubjectBetweenTheTwoParents() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        String familyId = familyOf(camille);
        String conversations = "/api/v1/families/" + familyId + "/conversations";

        // Seul : la messagerie n'est pas encore ouverte
        call(camille, HttpMethod.POST, conversations, open("Rentrée", "Bonjour")).andExpect(status().isConflict());
        join(camille, julien, familyId);

        String kimono = id(call(camille, HttpMethod.POST, conversations,
                open("  Kimono de Hugo  ", "  Hugo a oublié son kimono.  "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subject").value("Kimono de Hugo"))
                .andExpect(jsonPath("$.authorFirstName").value("Camille"))
                .andExpect(jsonPath("$.unreadCount").value(0))
                .andExpect(jsonPath("$.lastMessage.seq").value(1))
                .andExpect(jsonPath("$.lastMessage.body").value("Hugo a oublié son kimono."))
                .andExpect(jsonPath("$.lastMessage.senderId").value(camille.id())));
        String noel = id(call(julien, HttpMethod.POST, conversations, open("Vacances de Noël", "Je propose du 19 au 26."))
                .andExpect(status().isCreated()));
        String kimonoMessages = conversations + "/" + kimono + "/messages";
        call(julien, HttpMethod.POST, kimonoMessages, send("Merci, je préviens la maîtresse."))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seq").value(3))
                .andExpect(jsonPath("$.conversationId").value(kimono));
        call(camille, HttpMethod.POST, kimonoMessages, send("Parfait.")).andExpect(jsonPath("$.seq").value(4));

        // Liste : la plus récemment active d'abord, non-lus par discussion
        call(julien, conversations)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(kimono))
                .andExpect(jsonPath("$[0].lastMessage.body").value("Parfait."))
                .andExpect(jsonPath("$[0].unreadCount").value(2))
                .andExpect(jsonPath("$[1].id").value(noel))
                .andExpect(jsonPath("$[1].unreadCount").value(0));
        call(camille, conversations)
                .andExpect(jsonPath("$[0].unreadCount").value(1))
                .andExpect(jsonPath("$[1].unreadCount").value(1));

        // Messages d'une discussion seulement, par pages
        call(julien, kimonoMessages)
                .andExpect(jsonPath("$.messages[*].seq", contains(1, 3, 4)))
                .andExpect(jsonPath("$.hasMore").value(false));
        call(julien, kimonoMessages + "?limit=2")
                .andExpect(jsonPath("$.messages[*].seq", contains(3, 4)))
                .andExpect(jsonPath("$.hasMore").value(true));
        call(julien, kimonoMessages + "?limit=2&before=3")
                .andExpect(jsonPath("$.messages[*].seq", contains(1)))
                .andExpect(jsonPath("$.hasMore").value(false));

        // Julien lit la discussion du kimono : seuls les messages de Camille deviennent « lus »
        call(julien, HttpMethod.POST, conversations + "/" + kimono + "/read", "{\"upToSeq\":4}")
                .andExpect(status().isNoContent());
        call(camille, kimonoMessages)
                .andExpect(jsonPath("$.messages[0].readAt", notNullValue()))
                .andExpect(jsonPath("$.messages[1].readAt", nullValue()))
                .andExpect(jsonPath("$.messages[2].readAt", notNullValue()));
        call(julien, conversations)
                .andExpect(jsonPath("$[0].unreadCount").value(0))
                .andExpect(jsonPath("$[0].lastMessage.readAt", notNullValue()));
        // Relire ne change pas l'heure de lecture
        String readAt = JsonPath.read(call(camille, kimonoMessages).andReturn().getResponse().getContentAsString(),
                "$.messages[0].readAt");
        call(julien, HttpMethod.POST, conversations + "/" + kimono + "/read", "{\"upToSeq\":4}")
                .andExpect(status().isNoContent());
        call(camille, kimonoMessages).andExpect(jsonPath("$.messages[0].readAt").value(readAt));

        // Une seule chaîne de hash pour toute la famille, intacte
        UUID family = UUID.fromString(familyId);
        Boolean intact = transaction.execute(tx -> hasher.verify(messageRepository.findByFamilyIdOrderBySeqAsc(family)));
        assertThat(intact).isTrue();
    }

    @Test
    void discussionsAndMessagesCannotBeChangedOrDeletedEvenInTheDatabase() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        String familyId = familyOf(camille);
        join(camille, julien, familyId);
        String conversation = id(call(camille, HttpMethod.POST, "/api/v1/families/" + familyId + "/conversations",
                open("Sujet d'origine", "Texte d'origine")).andExpect(status().isCreated()));

        assertThatThrownBy(() -> jdbc.update("UPDATE messages SET body = 'Texte changé' WHERE family_id = UUID_TO_BIN(?)",
                familyId)).isInstanceOf(DataAccessException.class).hasMessageContaining("ne peut pas être modifié");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM messages WHERE family_id = UUID_TO_BIN(?)", familyId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ne peut pas être supprimé");
        assertThatThrownBy(() -> jdbc.update("UPDATE conversations SET subject = 'Autre' WHERE id = UUID_TO_BIN(?)",
                conversation)).isInstanceOf(DataAccessException.class).hasMessageContaining("ne peut pas être modifiée");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM conversations WHERE id = UUID_TO_BIN(?)", conversation))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ne peut pas être supprimée");
        // Aucune route de modification ou de suppression
        String messages = "/api/v1/families/" + familyId + "/conversations/" + conversation + "/messages";
        call(camille, HttpMethod.PUT, messages, send("x")).andExpect(status().is4xxClientError());
        call(camille, HttpMethod.DELETE, messages, null).andExpect(status().is4xxClientError());
        call(camille, HttpMethod.DELETE, "/api/v1/families/" + familyId + "/conversations/" + conversation, null)
                .andExpect(status().is4xxClientError());

        // Une modification faite malgré tout (triggers retirés) se verrait dans la chaîne
        UUID family = UUID.fromString(familyId);
        transaction.executeWithoutResult(tx -> {
            var stored = messageRepository.findByFamilyIdOrderBySeqAsc(family);
            assertThat(hasher.verify(stored)).isTrue();
            assertThat(hasher.hash(family, UUID.fromString(conversation), 1, UUID.fromString(camille.id()),
                    stored.getFirst().getCreatedAt(), null, "Texte changé")).isNotEqualTo(stored.getFirst().getHash());
        });
    }

    @Test
    void errors() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        Account stranger = verifiedUser("Inconnu");
        Account otherParent = verifiedUser("Autre");
        Account otherInvitee = verifiedUser("Invité");
        String familyId = familyOf(camille);
        join(camille, julien, familyId);
        String otherFamily = familyOf(otherParent);
        join(otherParent, otherInvitee, otherFamily);
        String conversations = "/api/v1/families/" + familyId + "/conversations";
        String conversation = id(call(camille, HttpMethod.POST, conversations, open("Rentrée", "Bonjour")));
        String messages = conversations + "/" + conversation + "/messages";

        call(camille, HttpMethod.POST, conversations, open("   ", "Bonjour")).andExpect(status().isBadRequest());
        call(camille, HttpMethod.POST, conversations, open("a".repeat(81), "Bonjour")).andExpect(status().isBadRequest());
        call(camille, HttpMethod.POST, conversations, open("Rentrée", " ")).andExpect(status().isBadRequest());
        call(camille, HttpMethod.POST, messages, send("   ")).andExpect(status().isBadRequest());
        call(camille, HttpMethod.POST, messages, send("a".repeat(2001))).andExpect(status().isBadRequest());
        call(camille, HttpMethod.POST, messages, send("a".repeat(2000))).andExpect(status().isCreated());
        call(camille, HttpMethod.POST, conversations + "/" + conversation + "/read", "{\"upToSeq\":0}")
                .andExpect(status().isBadRequest());
        // Non-membre, et discussion d'une autre famille ou inconnue
        call(stranger, conversations).andExpect(status().isNotFound());
        call(stranger, messages).andExpect(status().isNotFound());
        call(stranger, HttpMethod.POST, messages, send("Bonjour")).andExpect(status().isNotFound());
        String foreign = "/api/v1/families/" + otherFamily + "/conversations/" + conversation + "/messages";
        call(otherParent, foreign).andExpect(status().isNotFound());
        call(otherParent, HttpMethod.POST, foreign, send("Bonjour")).andExpect(status().isNotFound());
        call(camille, conversations + "/" + UUID.randomUUID() + "/messages").andExpect(status().isNotFound());
    }

    private static String open(String subject, String body) {
        return "{\"subject\":\"" + subject + "\",\"body\":\"" + body + "\"}";
    }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
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
