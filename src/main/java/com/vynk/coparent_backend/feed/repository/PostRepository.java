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

    /**
     * Une page du fil, la plus récente d'abord, avant {@code before} ; filtres facultatifs (enfant, premières fois).
     * {@code relativeId} : pour un proche, seulement le cercle « Famille », sans les publications masquées des autres.
     */
    @EntityGraph(attributePaths = {"createdBy", "authorRelative"})
    @Query("""
            select p from Post p left join p.authorRelative ar
            where p.family.id = :familyId and p.createdAt < :before
              and (:firstTime = false or p.firstTime = true)
              and (:childId is null or exists (select c from p.children c where c.id = :childId))
              and (:relativeId is null or (p.circle = com.vynk.coparent_backend.feed.entity.PostCircle.FAMILY
                   and (p.hiddenAt is null or ar.id = :relativeId)))
            order by p.createdAt desc""")
    List<Post> page(@Param("familyId") UUID familyId, @Param("before") Instant before,
                    @Param("childId") UUID childId, @Param("firstTime") boolean firstTime,
                    @Param("relativeId") UUID relativeId, Pageable pageable);

    /** Export de données. */
    @EntityGraph(attributePaths = {"createdBy", "authorRelative"})
    List<Post> findByFamilyIdOrderByCreatedAtDesc(UUID familyId);

    /** Export de données d'un proche : ses publications dans une famille. */
    @EntityGraph(attributePaths = {"authorRelative"})
    List<Post> findByAuthorRelativeIdOrderByCreatedAtDesc(UUID relativeId);
}
