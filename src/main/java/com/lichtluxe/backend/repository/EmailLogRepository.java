package com.lichtluxe.backend.repository;

import com.lichtluxe.backend.entity.EmailLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmailLogRepository extends JpaRepository<EmailLog, Long> {
    List<EmailLog> findByRecipientOrderByCreatedAtDesc(String recipient);
    List<EmailLog> findTop30ByOrderByCreatedAtDesc();
}
