package com.pgms.property;

import com.pgms.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "floors")
public class Floor extends AuditableEntity {

    public static final int DEFAULT_GRID_COLS = 24;
    public static final int DEFAULT_GRID_ROWS = 16;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "building_id", nullable = false, updatable = false)
    private Building building;

    @Column(name = "floor_number", nullable = false)
    private int floorNumber;

    private String label;

    /** Size of the layout editor's drawing grid; spaces are placed in whole grid units. */
    @Column(name = "grid_cols", nullable = false)
    private int gridCols = DEFAULT_GRID_COLS;

    @Column(name = "grid_rows", nullable = false)
    private int gridRows = DEFAULT_GRID_ROWS;

    protected Floor() {
    }

    public Floor(Building building, int floorNumber, String label) {
        this.building = building;
        this.floorNumber = floorNumber;
        this.label = label;
    }

    public void relabel(String label) {
        this.label = label;
    }

    /** Callers must check that every placed space still fits (see LayoutService). */
    public void resizeGrid(int gridCols, int gridRows) {
        this.gridCols = gridCols;
        this.gridRows = gridRows;
    }

    public Building getBuilding() {
        return building;
    }

    public int getFloorNumber() {
        return floorNumber;
    }

    public String getLabel() {
        return label;
    }

    public int getGridCols() {
        return gridCols;
    }

    public int getGridRows() {
        return gridRows;
    }
}
