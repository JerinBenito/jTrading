package com.jerin.trading.screen;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyScreenRepository extends JpaRepository<DailyScreen, Long> {

    Optional<DailyScreen> findByScreenDate(LocalDate screenDate);

    List<DailyScreen> findAllByOrderByScreenDateDesc(Pageable pageable);
}
