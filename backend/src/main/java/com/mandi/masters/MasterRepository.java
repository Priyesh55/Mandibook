package com.mandi.masters;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.NoRepositoryBean;

@NoRepositoryBean
public interface MasterRepository<T extends MasterEntity> extends JpaRepository<T, Long>, JpaSpecificationExecutor<T> {

    /** The only way to load a master record by id: it must belong to the given firm, otherwise it does not exist. */
    Optional<T> findByIdAndFirmId(Long id, Long firmId);
}
