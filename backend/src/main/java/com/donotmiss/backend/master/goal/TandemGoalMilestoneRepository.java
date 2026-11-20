package com.donotmiss.backend.master.goal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface TandemGoalMilestoneRepository extends JpaRepository<TandemGoalMilestoneEntity, Long> {
    List<TandemGoalMilestoneEntity> findByGoalIdOrderByPositionAscIdAsc(Long goalId);

    List<TandemGoalMilestoneEntity> findByGoalIdInOrderByPositionAscIdAsc(Collection<Long> goalIds);
}
