package com.vynk.coparent_backend.feed.repository;

import com.vynk.coparent_backend.feed.entity.Post;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PostRepository extends JpaRepository<Post, UUID> {

    Optional<Post> findByIdAndFamilyId(UUID id, UUID familyId);

    /** Une page du fil, la plus récente d'abord, avant {@code before} ; filtres facultatifs (enfant, premières fois). */
    @EntityGraph(attributePaths = "createdBy")
    @Query("""
            select p from Post p
            where p.family.id = :familyId and p.createdAt < :before
              and (:firstTime = false or p.firstTime = true)
              and (:childId is null or exists (select c from p.children c where c.id = :childId))
            order by p.createdAt desc""")
    List<Post> page(@Param("familyId") UUID familyId, @Param("before") Instant before,
                    @Param("childId") UUID childId, @Param("firstTime") boolean firstTime, Pageable pageable);

    /** Export de données. */
    @EntityGraph(attributePaths = "createdBy")
    List<Post> findByFamilyIdOrderByCreatedAtDesc(UUID familyId);
}
