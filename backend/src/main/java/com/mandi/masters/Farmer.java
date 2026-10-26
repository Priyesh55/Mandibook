package com.mandi.masters;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** A farmer (seller). The village is optional: farmers entered before V12 have none. */
@Entity
@Table(name = "farmer")
public class Farmer extends MasterEntity {

    @Column(name = "village_id")
    private Long villageId;

    public Long getVillageId() { return villageId; }
    public void setVillageId(Long villageId) { this.villageId = villageId; }
}
