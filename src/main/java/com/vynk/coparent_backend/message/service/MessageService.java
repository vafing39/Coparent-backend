package com.vynk.coparent_backend.message.service;

import com.vynk.coparent_backend.common.ApiException;
import com.vynk.coparent_backend.family.entity.Family;
import com.vynk.coparent_backend.family.entity.FamilyMember;
import com.vynk.coparent_backend.family.repository.FamilyMemberRepository;
import com.vynk.coparent_backend.family.repository.FamilyRepository;
import com.vynk.coparent_backend.family.service.FamilyAccess;
import com.vynk.coparent_backend.message.dto.MessagePageResponse;
import com.vynk.coparent_backend.message.dto.MessageRealtimeEvent;
import com.vynk.coparent_backend.message.dto.MessageResponse;
import com.vynk.coparent_backend.message.entity.Message;
import com.vynk.coparent_backend.message.entity.MessageRead;
import com.vynk.coparent_backend.message.realtime.MessagePublished;
import com.vynk.coparent_backend.message.repository.MessageReadRepository;
import com.vynk.coparent_backend.message.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Messagerie entre parents (cahier des charges, 7). Un message envoyé n'est jamais modifié ni supprimé ;
 * l'heure est celle du serveur ; chaque message porte le hash du précédent. La messagerie s'ouvre quand
 * l'autre parent a rejoint la famille.
 */
@Service
@RequiredArgsConstructor
public class MessageService {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;
    static final String SOLO = "La messagerie s'ouvrira quand l'autre parent aura rejoint.";

    private final FamilyAccess familyAccess;
    private final FamilyRepository familyRepository;
    private final FamilyMemberRepository memberRepository;
    private final MessageRepository repository;
    private final MessageReadRepository readRepository;
    private final MessageHasher hasher;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /** Une page de la conversation, avant le message {@code before} (les plus récents si null). */
    @Transactional(readOnly = true)
    public MessagePageResponse list(UUID familyId, UUID userId, Long before, Integer limit) {
        familyAccess.requireMember(familyId, userId);
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        Pageable page = Pageable.ofSize(size + 1);
        List<Message> newestFirst = before == null
                ? repository.findByFamilyIdOrderBySeqDesc(familyId, page)
                : repository.findByFamilyIdAndSeqLessThanOrderBySeqDesc(familyId, before, page);
        boolean hasMore = newestFirst.size() > size;
        List<Message> messages = new ArrayList<>(newestFirst.subList(0, Math.min(size, newestFirst.size())));
        Collections.reverse(messages);
        return new MessagePageResponse(toResponses(messages), hasMore);
    }

    @Transactional
    public MessageResponse send(UUID familyId, UUID userId, String text) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        if (memberRepository.countByFamilyId(familyId) < 2) {
            throw ApiException.conflict(SOLO);
        }
        // Un message à la fois par famille : seq et prev_hash se suivent sans trou.
        Family family = familyRepository.findByIdForUpdate(familyId)
                .orElseThrow(() -> ApiException.notFound("Famille introuvable."));
        Message last = repository.findFirstByFamilyIdOrderBySeqDesc(familyId).orElse(null);
        long seq = last == null ? 1 : last.getSeq() + 1;
        String prevHash = last == null ? null : last.getHash();
        String body = text.strip();
        if (body.isEmpty()) {
            throw ApiException.badRequest("Écrivez un message.");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS); // précision de DATETIME(6)
        String hash = hasher.hash(familyId, seq, userId, now, prevHash, body);
        Message message = repository.save(new Message(family, me.getUser(), seq, body, now, prevHash, hash));
        MessageResponse response = toResponse(message, null);
        events.publishEvent(new MessagePublished(familyId, MessageRealtimeEvent.message(response)));
        return response;
    }

    /** Les messages de l'autre parent jusqu'à {@code upToSeq} sont lus par moi. */
    @Transactional
    public void markRead(UUID familyId, UUID userId, long upToSeq) {
        familyAccess.requireMember(familyId, userId);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (readRepository.markRead(familyId, userId, upToSeq, now) > 0) {
            events.publishEvent(new MessagePublished(familyId, MessageRealtimeEvent.read(userId, upToSeq, now)));
        }
    }

    private List<MessageResponse> toResponses(List<Message> messages) {
        if (messages.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<MessageRead>> reads = readRepository
                .findByKeyMessageIdIn(messages.stream().map(Message::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(read -> read.getKey().messageId()));
        return messages.stream()
                .map(message -> toResponse(message, readAt(message, reads.getOrDefault(message.getId(), List.of()))))
                .toList();
    }

    /** Lu par quelqu'un d'autre que l'expéditeur. */
    private static Instant readAt(Message message, List<MessageRead> reads) {
        UUID senderId = message.getSender() == null ? null : message.getSender().getId();
        return reads.stream()
                .filter(read -> !read.getKey().userId().equals(senderId))
                .map(MessageRead::getReadAt)
                .min(Instant::compareTo)
                .orElse(null);
    }

    private static MessageResponse toResponse(Message message, Instant readAt) {
        var sender = message.getSender();
        return new MessageResponse(message.getId(), message.getSeq(), sender == null ? null : sender.getId(),
                sender == null ? null : sender.getFirstName(), message.getBody(), message.getCreatedAt(), readAt);
    }
}
