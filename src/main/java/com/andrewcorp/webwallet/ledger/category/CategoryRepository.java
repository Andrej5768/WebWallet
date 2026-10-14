package com.andrewcorp.webwallet.ledger.category;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByUserIdOrderBySortOrderAscNameAsc(Long userId);

    Optional<Category> findByIdAndUserId(Long id, Long userId);

    boolean existsByParentId(Long parentId);

    Optional<Category> findByUserIdAndExternalSourceAndExternalId(
            Long userId, String externalSource, String externalId);

    List<Category> findByParentId(Long parentId);
}
