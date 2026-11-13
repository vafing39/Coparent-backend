package com.vynk.coparent_backend.message.realtime;

import com.vynk.coparent_backend.common.ApiException;
import com.vynk.coparent_backend.family.service.FamilyAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contrôle d'accès du WebSocket. À {@code CONNECT}, le jeton d'accès (en-tête {@code Authorization: Bearer …})
 * est vérifié ; à {@code SUBSCRIBE}, seul un membre de la famille peut écouter ses messages. Les clients
 * n'envoient rien par le WebSocket : l'envoi passe par l'API REST.
 */
@Component
@RequiredArgsConstructor
public class StompAuthInterceptor implements ChannelInterceptor {

    private static final Pattern FAMILY_TOPIC =
            Pattern.compile("^/topic/families/([0-9a-fA-F-]{36})/messages$");

    private final JwtDecoder jwtDecoder;
    private final FamilyAccess familyAccess;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        switch (accessor.getCommand()) {
            case CONNECT -> accessor.setUser(authenticate(accessor.getFirstNativeHeader("Authorization")));
            case SUBSCRIBE -> authorizeSubscription(accessor.getUser(), accessor.getDestination());
            case SEND -> throw new MessagingException("Envoi non autorisé.");
            default -> {
            }
        }
        return message;
    }

    private Principal authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new MessagingException("Jeton manquant.");
        }
        try {
            String subject = jwtDecoder.decode(authorization.substring("Bearer ".length())).getSubject();
            return () -> subject;
        } catch (JwtException e) {
            throw new MessagingException("Jeton invalide.");
        }
    }

    private void authorizeSubscription(Principal user, String destination) {
        if (user == null || destination == null) {
            throw new MessagingException("Abonnement refusé.");
        }
        Matcher matcher = FAMILY_TOPIC.matcher(destination);
        if (!matcher.matches()) {
            throw new MessagingException("Abonnement refusé.");
        }
        try {
            familyAccess.requireMember(UUID.fromString(matcher.group(1)), UUID.fromString(user.getName()));
        } catch (ApiException | IllegalArgumentException e) {
            throw new MessagingException("Abonnement refusé.");
        }
    }
}
