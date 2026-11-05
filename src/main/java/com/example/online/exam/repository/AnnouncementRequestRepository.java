package com.example.online.exam.repository;

import com.example.online.exam.entity.AnnouncementRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnnouncementRequestRepository
        extends JpaRepository<AnnouncementRequest, String> {

    List<AnnouncementRequest> findTop20ByOrderByCreatedAtDesc();

    List<AnnouncementRequest> findTop100ByOrderByCreatedAtDesc();
}
