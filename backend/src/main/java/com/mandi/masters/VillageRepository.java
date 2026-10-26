package com.mandi.masters;

public interface VillageRepository extends MasterRepository<Village> {

    /** Villages of one firm by id (used to show the village name of a page of farmers). */
    java.util.List<Village> findByFirmIdAndIdIn(Long firmId, java.util.Collection<Long> ids);
}
