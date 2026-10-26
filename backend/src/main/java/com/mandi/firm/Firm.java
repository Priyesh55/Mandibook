package com.mandi.firm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A firm account. Read-only here: firms are created by migrations / an administrative procedure, not by the API. */
@Entity
@Table(name = "firm")
public class Firm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "name_hi", length = 200)
    private String nameHi;

    @Column(nullable = false)
    private boolean active = true;

    protected Firm() {
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getNameHi() { return nameHi; }
    public boolean isActive() { return active; }
}
