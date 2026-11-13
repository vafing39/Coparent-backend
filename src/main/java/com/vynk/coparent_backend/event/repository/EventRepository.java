package com.vynk.coparent_backend.event.repository;

import com.vynk.coparent_backend.event.entity.Event;
import com.vynk.coparent_backend.event.entity.EventVisibility;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, UUID> {

    /** Événements qui chevauchent [from, to) et que {@code userId} peut voir (partagés, ou privés à lui). */
    @EntityGraph(attributePaths = {"children", "author"})
    @Query("""
            select e from Event e
            where e.family.id = :familyId and e.startsAt < :to and e.endsAt > :from
              and e.repeat.frequency is null
              and (e.visibility = :shared or e.author.id = :userId)
            order by e.startsAt asc, e.createdAt asc
            """)
    List<Event> findVisible(@Param("familyId") UUID familyId, @Param("userId") UUID userId,
                            @Param("from") Instant from, @Param("to") Instant to,
                            @Param("shared") EventVisibility shared);

    /** Séries visibles commencées avant {@code to} et pas arrêtées avant {@code from}. */
    @EntityGraph(attributePaths = {"children", "author", "skippedDates", "repeatDays"})
    @Query("""
            select distinct e from Event e
            where e.family.id = :familyId and e.repeat.frequency is not null and e.startsAt < :to
              and (e.repeat.until is null or e.repeat.until >= :from)
              and (e.visibility = :shared or e.author.id = :userId)
            """)
    List<Event> findVisibleSeries(@Param("familyId") UUID familyId, @Param("userId") UUID userId,
                                  @Param("to") Instant to, @Param("from") LocalDate from,
                                  @Param("shared") EventVisibility shared);

    @EntityGraph(attributePaths = {"children", "author", "skippedDates", "repeatDays"})
    Optional<Event> findByIdAndFamilyId(UUID id, UUID familyId);
}
