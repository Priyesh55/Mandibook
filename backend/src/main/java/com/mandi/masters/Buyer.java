package com.mandi.masters;

import org.hibernate.annotations.BatchSize;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Batch-sized so a bill with many purchaser rows loads its buyers in one statement, not one per row. */
@Entity
@BatchSize(size = 50)
@Table(name = "buyer")
public class Buyer extends MasterEntity {
}
