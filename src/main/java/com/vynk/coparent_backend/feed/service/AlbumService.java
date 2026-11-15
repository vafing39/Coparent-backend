package com.vynk.coparent_backend.feed.service;

import com.vynk.coparent_backend.feed.dto.AlbumResponse;
import com.vynk.coparent_backend.feed.entity.PostPhoto;
import com.vynk.coparent_backend.feed.repository.PostPhotoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Album d'un enfant (Famille › Fil) : les photos du fil où il est identifié, groupées par année (heure de Paris).
 * Rien n'est rangé à la main ; mêmes règles de visibilité que le fil, sans les publications masquées.
 */
@Service
@RequiredArgsConstructor
public class AlbumService {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private final FeedAccess feedAccess;
    private final PostPhotoRepository photoRepository;

    /** {@code year} absent, ou sans photo : la plus récente qui en a. */
    @Transactional(readOnly = true)
    public AlbumResponse album(UUID familyId, UUID userId, UUID childId, Integer year) {
        FeedViewer me = feedAccess.require(familyId, userId);
        List<PostPhoto> photos = photoRepository.findAlbum(familyId, childId, !me.isParent());
        List<Integer> years = photos.stream()
                .map(this::year)
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();
        Integer shown = year != null && years.contains(year) ? year : years.isEmpty() ? null : years.getFirst();
        List<AlbumResponse.Photo> list = photos.stream()
                .filter(ph -> shown != null && year(ph) == shown)
                .map(ph -> new AlbumResponse.Photo(ph.getPost().getId(), ph.getId(), ph.getWidth(), ph.getHeight(),
                        ph.getPost().getCreatedAt()))
                .toList();
        return new AlbumResponse(years, shown, list);
    }

    private int year(PostPhoto photo) {
        return photo.getPost().getCreatedAt().atZone(PARIS).getYear();
    }
}
