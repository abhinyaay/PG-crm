package com.pgms.moveout;

import com.pgms.moveout.MoveOutEnums.ItemCondition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "move_out_inspection_items")
public class MoveOutInspectionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "move_out_id", nullable = false)
    private MoveOut moveOut;

    @Column(nullable = false)
    private String item;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_condition", nullable = false)
    private ItemCondition condition;

    @Column(name = "deduction_amount", nullable = false)
    private BigDecimal deductionAmount;

    private String note;

    protected MoveOutInspectionItem() {
    }

    MoveOutInspectionItem(MoveOut moveOut, String item, ItemCondition condition, BigDecimal deductionAmount, String note) {
        this.moveOut = moveOut;
        this.item = item;
        this.condition = condition;
        this.deductionAmount = deductionAmount == null ? BigDecimal.ZERO : deductionAmount;
        this.note = note;
    }

    public Long getId() {
        return id;
    }

    public String getItem() {
        return item;
    }

    public ItemCondition getCondition() {
        return condition;
    }

    public BigDecimal getDeductionAmount() {
        return deductionAmount;
    }

    public String getNote() {
        return note;
    }
}
