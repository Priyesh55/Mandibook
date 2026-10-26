package com.mandi.identity;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FirmInvitationRepository extends JpaRepository<FirmInvitation, Long> {

    Optional<FirmInvitation> findByIdAndFirmId(Long id, Long firmId);

    /** By token digest: the invitee presents a token and no firm, so this is the one deliberate lookup without a firm. */
    Optional<FirmInvitation> findByTokenHash(byte[] tokenHash);
}
