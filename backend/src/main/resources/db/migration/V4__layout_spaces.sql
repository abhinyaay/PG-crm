-- =====================================================================
-- V4: floor layout editor.
--   * floors get a drawing grid (grid_cols x grid_rows, in grid units)
--   * rooms become "spaces": a space_kind (ROOM, HALL, DORMITORY, COMMON_AREA, ...) plus optional geometry
--     on the floor grid, a display label and a colour. Existing rows become ROOM spaces, unplaced.
--   * beds get a free-text label, optional per-bed price overrides and an optional position inside the space
--   * bookings snapshot the price quoted at reservation time (bed override, else room price)
-- Overlap between spaces is checked by the application under a floor row lock (see LayoutService).
-- =====================================================================

-- ---------------------------------------------------------------------
-- Floors: drawing grid
-- ---------------------------------------------------------------------
ALTER TABLE floors
    ADD COLUMN grid_cols INTEGER NOT NULL DEFAULT 24,
    ADD COLUMN grid_rows INTEGER NOT NULL DEFAULT 16,
    ADD CONSTRAINT ck_floors_grid CHECK (grid_cols BETWEEN 1 AND 200 AND grid_rows BETWEEN 1 AND 200);

-- ---------------------------------------------------------------------
-- Rooms -> spaces
-- ---------------------------------------------------------------------
ALTER TABLE rooms
    ADD COLUMN space_kind VARCHAR(20) NOT NULL DEFAULT 'ROOM',
    ADD COLUMN layout_x   INTEGER,
    ADD COLUMN layout_y   INTEGER,
    ADD COLUMN layout_w   INTEGER,
    ADD COLUMN layout_h   INTEGER,
    ADD COLUMN label      VARCHAR(60),
    ADD COLUMN color      VARCHAR(7);

-- only ROOM spaces carry a sharing type; halls/dormitories use DORMITORY, bed-less spaces have none
ALTER TABLE rooms ALTER COLUMN room_type DROP NOT NULL;

-- halls/dormitories hold up to 50 beds and start empty; bed-less spaces always have capacity 0
ALTER TABLE rooms DROP CONSTRAINT ck_rooms_capacity;
ALTER TABLE rooms
    ADD CONSTRAINT ck_rooms_capacity CHECK (capacity BETWEEN 0 AND 50),
    ADD CONSTRAINT ck_rooms_space_kind CHECK (space_kind IN
        ('ROOM', 'HALL', 'DORMITORY', 'COMMON_AREA', 'KITCHEN', 'WASHROOM', 'LOUNGE', 'STUDY', 'STORE', 'OTHER')),
    ADD CONSTRAINT ck_rooms_kind_type CHECK (
        (space_kind = 'ROOM' AND room_type IS NOT NULL AND capacity BETWEEN 1 AND 20)
        OR (space_kind IN ('HALL', 'DORMITORY') AND room_type = 'DORMITORY')
        OR (space_kind NOT IN ('ROOM', 'HALL', 'DORMITORY') AND room_type IS NULL AND capacity = 0)),
    -- geometry is all-or-nothing: an unplaced space has no coordinates at all
    ADD CONSTRAINT ck_rooms_layout CHECK (
        (layout_x IS NULL AND layout_y IS NULL AND layout_w IS NULL AND layout_h IS NULL)
        OR (layout_x >= 0 AND layout_y >= 0 AND layout_w >= 1 AND layout_h >= 1)),
    ADD CONSTRAINT ck_rooms_color CHECK (color IS NULL OR color ~ '^#[0-9A-Fa-f]{6}$');

-- ---------------------------------------------------------------------
-- Beds: label, price overrides, position inside the space
-- ---------------------------------------------------------------------
ALTER TABLE beds
    ADD COLUMN label                     VARCHAR(60),
    ADD COLUMN monthly_rent_override     NUMERIC(12, 2),
    ADD COLUMN security_deposit_override NUMERIC(12, 2),
    ADD COLUMN pos_x                     INTEGER,
    ADD COLUMN pos_y                     INTEGER,
    ADD CONSTRAINT ck_beds_overrides CHECK ((monthly_rent_override IS NULL OR monthly_rent_override >= 0)
        AND (security_deposit_override IS NULL OR security_deposit_override >= 0)),
    ADD CONSTRAINT ck_beds_position CHECK ((pos_x IS NULL AND pos_y IS NULL)
        OR (pos_x BETWEEN 0 AND 1000 AND pos_y BETWEEN 0 AND 1000));

-- ---------------------------------------------------------------------
-- Bookings: price quoted at reservation (NULL for reservations made before V4)
-- ---------------------------------------------------------------------
ALTER TABLE bookings
    ADD COLUMN quoted_monthly_rent     NUMERIC(12, 2),
    ADD COLUMN quoted_security_deposit NUMERIC(12, 2),
    ADD CONSTRAINT ck_bookings_quote CHECK ((quoted_monthly_rent IS NULL OR quoted_monthly_rent >= 0)
        AND (quoted_security_deposit IS NULL OR quoted_security_deposit >= 0));

-- ---------------------------------------------------------------------
-- Analytics contract: existing columns unchanged, space_kind appended at the end
-- ---------------------------------------------------------------------
CREATE OR REPLACE VIEW analytics.rooms AS
SELECT r.id, r.property_id, r.room_number, r.room_type, r.capacity, r.monthly_rent,
       r.air_conditioned, r.attached_bathroom, r.status, r.space_kind
FROM rooms r
WHERE r.deleted_at IS NULL;
