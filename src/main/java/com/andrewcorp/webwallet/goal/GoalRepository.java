package com.andrewcorp.webwallet.goal;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoalRepository extends JpaRepository<Goal, Long> {

    Optional<Goal> findByIdAndUserId(Long id, Long userId);

    List<Goal> findByUserIdOrderBySortOrderAscIdAsc(Long userId);
}
