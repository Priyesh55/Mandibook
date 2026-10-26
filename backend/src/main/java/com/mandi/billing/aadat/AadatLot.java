package com.mandi.billing.aadat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.mandi.masters.Buyer;
import com.mandi.masters.Item;

import org.hibernate.annotations.BatchSize;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/** One lot of goods. Values are stored exactly as entered. The single buyer column is the older single-buyer form (A1); new bills record purchasers in aadat_lot_buyer. */
@Entity
@Table(name = "aadat_lot")
public class AadatLot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "firm_id", nullable = false, updatable = false)
    private Long firmId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id", nullable = false)
    private AadatBill bill;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    private Integer pieces;

    @Column(precision = 18, scale = 6)
    private BigDecimal weight;

    @Column(precision = 18, scale = 6)
    private BigDecimal rate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "buyer_id")
    private Buyer buyer;

    @Column(length = 60)
    private String marka;

    /** The farmer-side amount of the lot as TYPED by the operator (never computed by the system). */
    @Column(name = "lot_value", precision = 18, scale = 6)
    private BigDecimal lotValue;

    @OneToMany(mappedBy = "lot", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("seq")
    @BatchSize(size = 50)
    private List<AadatLotBuyer> purchasers = new ArrayList<>();

    public void addPurchaser(AadatLotBuyer p) {
        p.setLot(this);
        p.setFirmId(firmId);
        purchasers.add(p);
    }

    public Long getId() { return id; }
    public Long getFirmId() { return firmId; }
    void setFirmId(Long firmId) { this.firmId = firmId; }
    public AadatBill getBill() { return bill; }
    void setBill(AadatBill bill) { this.bill = bill; }
    public int getLineNo() { return lineNo; }
    public void setLineNo(int lineNo) { this.lineNo = lineNo; }
    public Item getItem() { return item; }
    public void setItem(Item item) { this.item = item; }
    public Integer getPieces() { return pieces; }
    public void setPieces(Integer pieces) { this.pieces = pieces; }
    public BigDecimal getWeight() { return weight; }
    public void setWeight(BigDecimal weight) { this.weight = weight; }
    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal rate) { this.rate = rate; }
    public Buyer getBuyer() { return buyer; }
    public void setBuyer(Buyer buyer) { this.buyer = buyer; }
    public String getMarka() { return marka; }
    public void setMarka(String marka) { this.marka = marka; }
    public BigDecimal getLotValue() { return lotValue; }
    public void setLotValue(BigDecimal lotValue) { this.lotValue = lotValue; }
    public List<AadatLotBuyer> getPurchasers() { return purchasers; }
}
