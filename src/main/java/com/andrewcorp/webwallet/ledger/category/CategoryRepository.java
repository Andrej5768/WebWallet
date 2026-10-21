package com.andrewcorp.webwallet.ledger.category;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByUserIdOrderBySortOrderAscNameAsc(Long userId);

    /** All categories of the user with their levels. */
    default CategoryTree tree(Long userId) {
        return CategoryTree.of(findByUserIdOrderBySortOrderAscNameAsc(userId));
    }

    Optional<Category> findByIdAndUserId(Long id, Long userId);

    boolean existsByParentId(Long parentId);

    @Query("select c.id from Category c where c.userId = :userId and c.parentId = :parentId")
    List<Long> findChildIds(Long userId, Long parentId);

    Optional<Category> findByUserIdAndExternalSourceAndExternalId(
            Long userId, String externalSource, String externalId);

    List<Category> findByParentId(Long parentId);
}
