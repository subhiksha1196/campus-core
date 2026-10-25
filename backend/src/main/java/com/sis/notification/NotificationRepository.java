package com.sis.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface NotificationRepository extends MongoRepository<Notification, String> {
    Page<Notification> findByStudentId(String studentId, Pageable pageable);

    Page<Notification> findByStudentIdAndReadFalse(String studentId, Pageable pageable);

    long countByStudentIdAndReadFalse(String studentId);
}
