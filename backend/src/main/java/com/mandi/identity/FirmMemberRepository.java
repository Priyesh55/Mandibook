package com.mandi.identity;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FirmMemberRepository extends JpaRepository<FirmMember, Long> {

    Optional<FirmMember> findByIdAndFirmId(Long id, Long firmId);

    Optional<FirmMember> findByFirmIdAndUserId(Long firmId, Long userId);

    /** The firms a person may use: paged, because an owner has no firm-count limit. */
    Page<FirmMember> findByUserIdAndStatus(Long userId, MemberStatus status, Pageable page);
}
