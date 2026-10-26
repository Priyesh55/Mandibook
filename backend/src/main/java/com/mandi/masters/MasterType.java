package com.mandi.masters;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.Supplier;

public enum MasterType {
    FARMERS("farmers", "FARMER", Farmer::new),
    BUYERS("buyers", "BUYER", Buyer::new),
    VILLAGES("villages", "VILLAGE", Village::new),
    ITEMS("items", "ITEM", Item::new),
    EXPENSE_HEADS("expense-heads", "EXPENSE_HEAD", ExpenseHead::new);

    private final String path;
    private final String auditName;
    private final Supplier<? extends MasterEntity> factory;

    MasterType(String path, String auditName, Supplier<? extends MasterEntity> factory) {
        this.path = path;
        this.auditName = auditName;
        this.factory = factory;
    }

    public MasterEntity newEntity() {
        return factory.get();
    }

    /** Entity type name used in the audit history (singular, upper case). */
    public String auditName() {
        return auditName;
    }

    public static Optional<MasterType> fromPath(String path) {
        return Arrays.stream(values()).filter(t -> t.path.equals(path)).findFirst();
    }
}
