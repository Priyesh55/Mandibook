package com.mandi.firm;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FirmRepository extends JpaRepository<Firm, Long> {

    Optional<Firm> findByCode(String code);
}
