ALTER TABLE inventory_items
    ADD CONSTRAINT inventory_available_nonnegative CHECK (available_quantity >= 0),
    ADD CONSTRAINT inventory_reserved_nonnegative CHECK (reserved_quantity >= 0);
