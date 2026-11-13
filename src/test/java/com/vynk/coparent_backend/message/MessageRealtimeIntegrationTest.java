package com.vynk.coparent_backend.message;

import com.jayway.jsonpath.JsonPath;
import com.vynk.coparent_backend.auth.email.EmailSender;
import com.vynk.coparent_backend.auth.service.RateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.SimpleMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real-time messaging over STOMP on /ws: authentication, family-only subscriptions, MESSAGE and READ events. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.check-compromised-passwords=false",
        "app.auth.jwt-secret=test-only-secret-that-is-at-least-32-bytes-long"
})
@AutoConfigureMockMvc
class MessageRealtimeIntegrationTest {

    private static final String TEST_DOMAIN = "@test.coparent.invalid";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String FAMILY = "{\"name\":\"Famille de Léa et Hugo\",\"vacationZone\":\"A\"}";

    @LocalServerPort
    private int port;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RateLimiter rateLimiter;
    @MockitoBean
    private EmailSender emailSender;

    private final WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());

    @BeforeEach
    void setUp() {
        rateLimiter.reset();
        stompClient.setMessageConverter(new SimpleMessageConverter());
    }

    @AfterEach
    void deleteTestData() {
        stompClient.stop();
        jdbc.update("DELETE f FROM families f JOIN family_members m ON m.family_id = f.id "
                + "JOIN users u ON u.id = m.user_id WHERE u.email LIKE ?", "%" + TEST_DOMAIN);
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + TEST_DOMAIN);
    }

    @Test
    void theOtherParentReceivesMessagesAndReadReceipts() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        String familyId = familyOf(camille);
        join(camille, julien, familyId);
        String messages = "/api/v1/families/" + familyId + "/messages";

        StompSession session = connect(julien.accessToken());
        BlockingQueue<String> received = subscribe(session, "/topic/families/" + familyId + "/messages");

        call(camille, HttpMethod.POST, messages, "{\"body\":\"Bonjour\"}").andExpect(status().isCreated());
        String event = received.poll(5, TimeUnit.SECONDS);
        assertThat(event).isNotNull();
        assertThat((String) JsonPath.read(event, "$.type")).isEqualTo("MESSAGE");
        assertThat((String) JsonPath.read(event, "$.message.body")).isEqualTo("Bonjour");
        assertThat((String) JsonPath.read(event, "$.message.senderId")).isEqualTo(camille.id());
        assertThat((String) JsonPath.read(event, "$.message.createdAt")).endsWith("Z");

        call(julien, HttpMethod.POST, messages + "/read", "{\"upToSeq\":1}").andExpect(status().isNoContent());
        String read = received.poll(5, TimeUnit.SECONDS);
        assertThat(read).isNotNull();
        assertThat((String) JsonPath.read(read, "$.type")).isEqualTo("READ");
        assertThat((String) JsonPath.read(read, "$.readerId")).isEqualTo(julien.id());
        assertThat((Integer) JsonPath.read(read, "$.upToSeq")).isEqualTo(1);
    }

    @Test
    void connectionWithoutAValidTokenIsRefused() {
        assertThatThrownBy(() -> connect(null)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> connect("not-a-jwt")).isInstanceOf(Exception.class);
    }

    @Test
    void anotherFamilyCannotBeListenedTo() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        Account stranger = verifiedUser("Inconnu");
        String familyId = familyOf(camille);
        join(camille, julien, familyId);

        StompSession session = connect(stranger.accessToken());
        BlockingQueue<String> received = subscribe(session, "/topic/families/" + familyId + "/messages");
        call(camille, HttpMethod.POST, "/api/v1/families/" + familyId + "/messages", "{\"body\":\"Privé\"}")
                .andExpect(status().isCreated());

        assertThat(received.poll(2, TimeUnit.SECONDS)).isNull();
        assertThat(session.isConnected()).isFalse(); // le serveur ferme la session après le refus
    }

    private StompSession connect(String token) throws Exception {
        StompHeaders headers = new StompHeaders();
        if (token != null) {
            headers.add("Authorization", "Bearer " + token);
        }
        return stompClient.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), headers,
                new StompSessionHandlerAdapter() { }).get(5, TimeUnit.SECONDS);
    }

    private static BlockingQueue<String> subscribe(StompSession session, String destination) throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add(new String((byte[]) payload, StandardCharsets.UTF_8));
            }
        });
        Thread.sleep(300); // laisse le serveur enregistrer l'abonnement
        return received;
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
