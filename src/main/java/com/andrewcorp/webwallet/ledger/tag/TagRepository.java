package com.andrewcorp.webwallet.ledger.tag;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<Tag, Long> {

    List<Tag> findByUserIdOrderByNameAsc(Long userId);

    Optional<Tag> findByIdAndUserId(Long id, Long userId);

    List<Tag> findByUserIdAndIdIn(Long userId, Collection<Long> ids);

    boolean existsByUserIdAndNameIgnoreCase(Long userId, String name);
}
