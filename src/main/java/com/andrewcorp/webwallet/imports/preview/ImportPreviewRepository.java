package com.andrewcorp.webwallet.imports.preview;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ImportPreviewRepository extends JpaRepository<ImportPreview, UUID> {

    Optional<ImportPreview> findByIdAndUserIdAndExpiresAtAfter(UUID id, Long userId, Instant now);

    /** Ids of the previews of the user, newest first; the stored JSON is not loaded. */
    @Query("select p.id from ImportPreview p where p.userId = :userId order by p.createdAt desc")
    List<UUID> findIdsNewestFirst(Long userId);

    @Modifying
    @Query("delete from ImportPreview p where p.expiresAt <= :now")
    int deleteExpired(Instant now);
}
