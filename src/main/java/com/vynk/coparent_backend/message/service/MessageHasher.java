package com.vynk.coparent_backend.message.service;

import com.vynk.coparent_backend.message.entity.Message;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Hash chaîné des messages : SHA-256 de leur contenu et du hash du message précédent de la famille. Modifier
 * ou retirer un message casse la chaîne à partir de lui.
 */
@Component
public class MessageHasher {

    public String hash(UUID familyId, long seq, UUID senderId, Instant createdAt, String prevHash, String body) {
        String canonical = String.join("|", "v1", familyId.toString(), Long.toString(seq),
                Objects.toString(senderId, ""), createdAt.toString(), Objects.toString(prevHash, ""), body);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Vrai si la conversation (dans l'ordre de {@code seq}) est intacte. L'expéditeur fait partie du hash : la
     * chaîne ne se vérifie plus après la suppression de son compte (sender_id devenu NULL).
     */
    public boolean verify(List<Message> conversation) {
        String previous = null;
        long expectedSeq = 1;
        for (Message message : conversation) {
            UUID senderId = message.getSender() == null ? null : message.getSender().getId();
            boolean linked = message.getSeq() == expectedSeq && Objects.equals(message.getPrevHash(), previous);
            String expected = hash(message.getFamily().getId(), message.getSeq(), senderId, message.getCreatedAt(),
                    message.getPrevHash(), message.getBody());
            if (!linked || !expected.equals(message.getHash())) {
                return false;
            }
            previous = message.getHash();
            expectedSeq++;
        }
        return true;
    }
}
