package com.vynk.coparent_backend.account.service;

import com.vynk.coparent_backend.account.dto.DataExport;
import com.vynk.coparent_backend.auth.email.EmailSender;
import com.vynk.coparent_backend.auth.service.RateLimiter;
import com.vynk.coparent_backend.auth.service.RateLimiter.Action;
import com.vynk.coparent_backend.bag.repository.BagItemRepository;
import com.vynk.coparent_backend.common.ApiException;
import com.vynk.coparent_backend.event.entity.Event;
import com.vynk.coparent_backend.event.entity.EventVisibility;
import com.vynk.coparent_backend.event.repository.EventRepository;
import com.vynk.coparent_backend.exchange.entity.ExchangeStatus;
import com.vynk.coparent_backend.document.repository.DocumentEventRepository;
import com.vynk.coparent_backend.document.repository.DocumentRepository;
import com.vynk.coparent_backend.expense.entity.ExpenseStatus;
import com.vynk.coparent_backend.expense.entity.SettlementStatus;
import com.vynk.coparent_backend.expense.repository.ExpenseRepository;
import com.vynk.coparent_backend.expense.repository.SettlementRepository;
import com.vynk.coparent_backend.exchange.repository.CustodyExchangeRepository;
import com.vynk.coparent_backend.family.entity.Child;
import com.vynk.coparent_backend.family.entity.FamilyMember;
import com.vynk.coparent_backend.family.repository.ChildProfileRepository;
import com.vynk.coparent_backend.family.repository.FamilyMemberRepository;
import com.vynk.coparent_backend.family.service.FamilyService;
import com.vynk.coparent_backend.feed.entity.Post;
import com.vynk.coparent_backend.feed.repository.PostCommentRepository;
import com.vynk.coparent_backend.feed.repository.PostReactionRepository;
import com.vynk.coparent_backend.feed.repository.PostRepository;
import com.vynk.coparent_backend.message.entity.Message;
import com.vynk.coparent_backend.message.repository.ConversationRepository;
import com.vynk.coparent_backend.message.repository.MessageRepository;
import com.vynk.coparent_backend.todo.repository.ToDoItemRepository;
import com.vynk.coparent_backend.user.entity.User;
import com.vynk.coparent_backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** « Télécharger mes données » (spec §1): the user's data as JSON, sent by email to the account's address. */
@Service
@RequiredArgsConstructor
public class DataExportService {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private final UserRepository userRepository;
    private final FamilyMemberRepository memberRepository;
    private final FamilyService familyService;
    private final EventRepository eventRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final CustodyExchangeRepository exchangeRepository;
    private final BagItemRepository bagItemRepository;
    private final ExpenseRepository expenseRepository;
    private final SettlementRepository settlementRepository;
    private final DocumentRepository documentRepository;
    private final ChildProfileRepository childProfileRepository;
    private final PostRepository postRepository;
    private final PostReactionRepository postReactionRepository;
    private final PostCommentRepository postCommentRepository;
    private final DocumentEventRepository documentEventRepository;
    private final ToDoItemRepository toDoItemRepository;
    private final EmailSender emailSender;
    private final RateLimiter rateLimiter;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    /** Builds the export and emails it (sending is asynchronous). Three per day at most. */
    @Transactional(readOnly = true)
    public void sendExport(UUID userId) {
        User user = userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> ApiException.unauthorized("Session invalide."));
        rateLimiter.consume(Action.DATA_EXPORT, userId.toString());
        Instant now = clock.instant();
        byte[] json = jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(build(user, now));
        String fileName = "coparent-mes-donnees-" + now.atZone(PARIS).toLocalDate() + ".json";
        emailSender.sendDataExport(user, fileName, json);
    }

    DataExport build(User user, Instant now) {
        DataExport.Account account = new DataExport.Account(user.getId(), user.getEmail(), user.getFirstName(),
                user.getEmailVerifiedAt(), user.getCreatedAt());
        Optional<FamilyMember> membership = memberRepository.findFirstByUserId(user.getId());
        if (membership.isEmpty()) {
            return new DataExport(now, account, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of());
        }
        UUID familyId = membership.get().getFamily().getId();
        return new DataExport(now, account, familyService.getMine(user.getId()), childProfiles(familyId),
                events(familyId, user.getId()), conversations(familyId), exchanges(familyId), bagItems(familyId),
                expenses(familyId), settlements(familyId), documents(familyId), posts(familyId), openRequests(familyId));
    }

    private List<DataExport.Event> events(UUID familyId, UUID userId) {
        return eventRepository.findAllVisible(familyId, userId, EventVisibility.SHARED).stream()
                .map(this::event)
                .toList();
    }

    private DataExport.Event event(Event e) {
        String repeat = e.getRepeat() == null || e.getRepeat().getFrequency() == null
                ? null : e.getRepeat().getFrequency().name();
        return new DataExport.Event(e.getId(), e.getTitle(), e.getType().name(), e.isAllDay(), e.getStartsAt(),
                e.getEndsAt(), e.getLocation(), e.getVisibility().name(), repeat,
                e.getChildren().stream().map(Child::getFirstName).sorted().toList(),
                firstName(e.getAuthor()), e.getCreatedAt());
    }

    private List<DataExport.Conversation> conversations(UUID familyId) {
        Map<UUID, List<DataExport.Message>> messages = messageRepository.findByFamilyIdOrderBySeqAsc(familyId)
                .stream()
                .collect(Collectors.groupingBy(m -> m.getConversation().getId(),
                        Collectors.mapping(this::message, Collectors.toList())));
        return conversationRepository.findByFamilyId(familyId).stream()
                .sorted(Comparator.comparing(c -> c.getCreatedAt()))
                .map(c -> new DataExport.Conversation(c.getId(), c.getSubject(), firstName(c.getAuthor()),
                        c.getCreatedAt(), messages.getOrDefault(c.getId(), List.of())))
                .toList();
    }

    private DataExport.Message message(Message m) {
        return new DataExport.Message(m.getSeq(), firstName(m.getSender()), m.getBody(), m.getCreatedAt());
    }

    private List<DataExport.Exchange> exchanges(UUID familyId) {
        return exchangeRepository
                .findByFamilyIdAndStatusInOrderByTakeFromAsc(familyId, EnumSet.allOf(ExchangeStatus.class))
                .stream()
                .map(x -> new DataExport.Exchange(x.getId(), firstName(x.getRequestedBy()), x.getTakeFrom(),
                        x.getTakeTo(), x.getGiveFrom(), x.getGiveTo(), x.getMessage(), x.getStatus().name(),
                        x.getCreatedAt(), x.getAnsweredAt()))
                .toList();
    }

    private List<DataExport.Expense> expenses(UUID familyId) {
        return expenseRepository
                .findByFamilyIdAndStatusInOrderByPaidOnDescCreatedAtDesc(familyId, EnumSet.allOf(ExpenseStatus.class))
                .stream()
                .map(e -> new DataExport.Expense(e.getId(), e.getLabel(), e.getAmountCents(), e.getCategory().name(),
                        e.getChildren().stream().map(Child::getFirstName).sorted().toList(), e.getPayerRole().name(),
                        e.getPaidOn(), e.getPayerSharePercent(), e.getStatus().name(),
                        e.getContestReason() == null ? null : e.getContestReason().name(), e.getContestNote(),
                        e.hasReceipt(), firstName(e.getCreatedBy()), e.getCreatedAt()))
                .toList();
    }

    private List<DataExport.ChildProfile> childProfiles(UUID familyId) {
        return childProfileRepository.findByChildFamilyId(familyId).stream()
                .map(p -> new DataExport.ChildProfile(p.getChild().getFirstName(), p.getSchool(), p.getGrade(),
                        p.getTeacher(), p.getAllergies(), p.getDoctor(), p.getDoctorPhone(),
                        p.getContacts().stream()
                                .map(c -> new DataExport.ChildContact(c.getName(), c.getRole(), c.getPhone()))
                                .toList(),
                        firstName(p.getUpdatedBy()), p.getUpdatedAt()))
                .toList();
    }

    private List<DataExport.Post> posts(UUID familyId) {
        List<Post> posts = postRepository.findByFamilyIdOrderByCreatedAtDesc(familyId);
        Map<UUID, List<String>> reactions = postReactionRepository
                .findByPostIdIn(posts.stream().map(Post::getId).toList()).stream()
                .collect(Collectors.groupingBy(r -> r.getPostId(),
                        Collectors.mapping(r -> r.getKind().name() + " " + r.getRole().name(), Collectors.toList())));
        Map<UUID, List<DataExport.PostComment>> comments =
                postCommentRepository.findByPostFamilyIdOrderByCreatedAtAsc(familyId).stream()
                        .collect(Collectors.groupingBy(c -> c.getPost().getId(),
                                Collectors.mapping(c -> new DataExport.PostComment(firstName(c.getUser()), c.getBody(),
                                        c.getCreatedAt()), Collectors.toList())));
        return posts.stream()
                .map(p -> new DataExport.Post(p.getId(), firstName(p.getCreatedBy()), p.getBody(), p.isFirstTime(),
                        p.getChildren().stream().map(Child::getFirstName).toList(), p.getPhotos().size(),
                        reactions.getOrDefault(p.getId(), List.of()), p.isHidden(), p.getCreatedAt(),
                        comments.getOrDefault(p.getId(), List.of())))
                .toList();
    }

    private List<DataExport.Document> documents(UUID familyId) {
        Map<UUID, List<DataExport.DocumentEvent>> history =
                documentEventRepository.findByDocumentFamilyIdOrderByCreatedAtDesc(familyId).stream()
                        .collect(Collectors.groupingBy(e -> e.getDocument().getId(),
                                Collectors.mapping(e -> new DataExport.DocumentEvent(e.getKind().name(),
                                        firstName(e.getUser()), e.getCreatedAt()), Collectors.toList())));
        return documentRepository.findByFamilyIdOrderByCreatedAtDesc(familyId).stream()
                .map(d -> new DataExport.Document(d.getId(), d.getTitle(), d.getCategory().name(),
                        d.getChild() == null ? null : d.getChild().getFirstName(), d.getContentType(),
                        d.getSizeBytes(), firstName(d.getCreatedBy()), d.getCreatedAt(), d.getDeletedAt(),
                        history.getOrDefault(d.getId(), List.of())))
                .toList();
    }

    private List<DataExport.Settlement> settlements(UUID familyId) {
        return settlementRepository
                .findByFamilyIdAndStatusInOrderByDeclaredAtDesc(familyId, EnumSet.allOf(SettlementStatus.class))
                .stream()
                .map(s -> new DataExport.Settlement(s.getId(), s.getPayerRole().name(), s.getAmountCents(),
                        s.getStatus().name(), s.getDeclaredAt(), s.getAnsweredAt()))
                .toList();
    }

    private List<DataExport.BagItem> bagItems(UUID familyId) {
        return bagItemRepository.findByFamilyIdOrderByCreatedAtAsc(familyId).stream()
                .map(item -> new DataExport.BagItem(item.getChild().getFirstName(), item.getLabel(),
                        item.getCreatedAt()))
                .toList();
    }

    private List<DataExport.ToDo> openRequests(UUID familyId) {
        return toDoItemRepository.findByFamilyIdAndResolvedAtIsNullOrderByRequestedAtAsc(familyId).stream()
                .map(item -> new DataExport.ToDo(item.getKind().name(), item.getSubject(),
                        firstName(item.getRequestedBy()), firstName(item.getAssignee()), item.getRequestedAt()))
                .toList();
    }

    private static String firstName(User user) {
        return user == null ? null : user.getFirstName();
    }
}
