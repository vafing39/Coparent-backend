package com.vynk.coparent_backend.message.service;

import com.vynk.coparent_backend.common.ApiException;
import com.vynk.coparent_backend.family.entity.Family;
import com.vynk.coparent_backend.family.entity.FamilyMember;
import com.vynk.coparent_backend.family.repository.FamilyMemberRepository;
import com.vynk.coparent_backend.family.repository.FamilyRepository;
import com.vynk.coparent_backend.family.service.FamilyAccess;
import com.vynk.coparent_backend.message.dto.ConversationResponse;
import com.vynk.coparent_backend.message.dto.MessagePageResponse;
import com.vynk.coparent_backend.message.dto.MessageRealtimeEvent;
import com.vynk.coparent_backend.message.dto.MessageResponse;
import com.vynk.coparent_backend.message.entity.Conversation;
import com.vynk.coparent_backend.message.entity.Message;
import com.vynk.coparent_backend.message.entity.MessageRead;
import com.vynk.coparent_backend.message.realtime.MessagePublished;
import com.vynk.coparent_backend.message.repository.ConversationRepository;
import com.vynk.coparent_backend.message.repository.MessageReadRepository;
import com.vynk.coparent_backend.message.repository.MessageRepository;
import com.vynk.coparent_backend.user.entity.User;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Messagerie entre parents (cahier des charges, 7), rangée en discussions par sujet (écart voulu : une
 * conversation unique devenait longue à parcourir). Un message envoyé n'est jamais modifié ni supprimé ;
 * l'heure est celle du serveur ; chaque message porte le hash du précédent de la famille. La messagerie
 * s'ouvre quand l'autre parent a rejoint la famille.
 */
@Service
@RequiredArgsConstructor
public class MessageService {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;
    static final String SOLO = "La messagerie s'ouvrira quand l'autre parent aura rejoint.";
    static final String CONVERSATION_NOT_FOUND = "Discussion introuvable.";

    private final FamilyAccess familyAccess;
    private final FamilyRepository familyRepository;
    private final FamilyMemberRepository memberRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository repository;
    private final MessageReadRepository readRepository;
    private final MessageHasher hasher;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /** Les discussions de la famille, la plus récemment active d'abord. */
    @Transactional(readOnly = true)
    public List<ConversationResponse> conversations(UUID familyId, UUID userId) {
        familyAccess.requireMember(familyId, userId);
        Map<UUID, MessageResponse> lastMessages = toResponses(repository.findLastOfEachConversation(familyId))
                .stream()
                .collect(Collectors.toMap(MessageResponse::conversationId, Function.identity()));
        Map<UUID, Long> unread = repository.countUnreadByConversation(familyId, userId).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));
        return conversationRepository.findByFamilyId(familyId).stream()
                .filter(conversation -> lastMessages.containsKey(conversation.getId()))
                .map(conversation -> toResponse(conversation, lastMessages.get(conversation.getId()),
                        unread.getOrDefault(conversation.getId(), 0L)))
                .sorted(Comparator.comparingLong(
                        (ConversationResponse conversation) -> conversation.lastMessage().seq()).reversed())
                .toList();
    }

    /** Nouvelle discussion, avec son premier message. */
    @Transactional
    public ConversationResponse createConversation(UUID familyId, UUID userId, String subject, String text) {
        FamilyMember me = requireOpen(familyId, userId);
        String cleanSubject = requireText(subject, "Indiquez le sujet de la discussion.");
        String body = requireText(text, "Écrivez un message.");
        Family family = lockFamily(familyId);
        Instant now = now();
        Conversation conversation = conversationRepository.save(
                new Conversation(family, me.getUser(), cleanSubject, now));
        MessageResponse first = append(family, conversation, me.getUser(), body, now);
        return toResponse(conversation, first, 0);
    }

    /** Une page de la discussion, avant le message {@code before} (les plus récents si null). */
    @Transactional(readOnly = true)
    public MessagePageResponse list(UUID familyId, UUID userId, UUID conversationId, Long before, Integer limit) {
        familyAccess.requireMember(familyId, userId);
        requireConversation(familyId, conversationId);
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        Pageable page = Pageable.ofSize(size + 1);
        List<Message> newestFirst = before == null
                ? repository.findByConversationIdOrderBySeqDesc(conversationId, page)
                : repository.findByConversationIdAndSeqLessThanOrderBySeqDesc(conversationId, before, page);
        boolean hasMore = newestFirst.size() > size;
        List<Message> messages = new ArrayList<>(newestFirst.subList(0, Math.min(size, newestFirst.size())));
        Collections.reverse(messages);
        return new MessagePageResponse(toResponses(messages), hasMore);
    }

    @Transactional
    public MessageResponse send(UUID familyId, UUID userId, UUID conversationId, String text) {
        FamilyMember me = requireOpen(familyId, userId);
        Conversation conversation = requireConversation(familyId, conversationId);
        String body = requireText(text, "Écrivez un message.");
        Family family = lockFamily(familyId);
        return append(family, conversation, me.getUser(), body, now());
    }

    /** Les messages de l'autre parent dans la discussion, jusqu'à {@code upToSeq}, sont lus par moi. */
    @Transactional
    public void markRead(UUID familyId, UUID userId, UUID conversationId, long upToSeq) {
        familyAccess.requireMember(familyId, userId);
        requireConversation(familyId, conversationId);
        Instant now = now();
        if (readRepository.markRead(conversationId, userId, upToSeq, now) > 0) {
            events.publishEvent(new MessagePublished(familyId,
                    MessageRealtimeEvent.read(conversationId, userId, upToSeq, now)));
        }
    }

    // --- helpers ---

    private FamilyMember requireOpen(UUID familyId, UUID userId) {
        FamilyMember me = familyAccess.requireMember(familyId, userId);
        if (memberRepository.countByFamilyId(familyId) < 2) {
            throw ApiException.conflict(SOLO);
        }
        return me;
    }

    private Conversation requireConversation(UUID familyId, UUID conversationId) {
        return conversationRepository.findByIdAndFamilyId(conversationId, familyId)
                .orElseThrow(() -> ApiException.notFound(CONVERSATION_NOT_FOUND));
    }

    /** Un message à la fois par famille : seq et prev_hash se suivent sans trou. */
    private Family lockFamily(UUID familyId) {
        return familyRepository.findByIdForUpdate(familyId)
                .orElseThrow(() -> ApiException.notFound("Famille introuvable."));
    }

    private static String requireText(String text, String error) {
        String clean = text == null ? "" : text.strip();
        if (clean.isEmpty()) {
            throw ApiException.badRequest(error);
        }
        return clean;
    }

    /** Précision de DATETIME(6). */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** Ajoute un message à la chaîne de la famille (famille déjà verrouillée) et le publie en temps réel. */
    private MessageResponse append(Family family, Conversation conversation, User sender, String body, Instant now) {
        Message last = repository.findFirstByFamilyIdOrderBySeqDesc(family.getId()).orElse(null);
        long seq = last == null ? 1 : last.getSeq() + 1;
        String prevHash = last == null ? null : last.getHash();
        String hash = hasher.hash(family.getId(), conversation.getId(), seq, sender.getId(), now, prevHash, body);
        Message message = repository.save(new Message(family, conversation, sender, seq, body, now, prevHash, hash));
        MessageResponse response = toResponse(message, null);
        events.publishEvent(new MessagePublished(family.getId(), MessageRealtimeEvent.message(response)));
        return response;
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
        return new MessageResponse(message.getId(), message.getConversation().getId(), message.getSeq(),
                sender == null ? null : sender.getId(), sender == null ? null : sender.getFirstName(),
                message.getBody(), message.getCreatedAt(), readAt);
    }

    private static ConversationResponse toResponse(Conversation conversation, MessageResponse lastMessage,
                                                   long unreadCount) {
        var author = conversation.getAuthor();
        return new ConversationResponse(conversation.getId(), conversation.getSubject(),
                author == null ? null : author.getFirstName(), conversation.getCreatedAt(), lastMessage, unreadCount);
    }
}
