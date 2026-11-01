-- This is Inventory's source version, independent of Product's optimistic-lock version.
ALTER TABLE products ADD COLUMN inventory_version BIGINT NOT NULL DEFAULT -1;
ALTER TABLE products ADD CONSTRAINT chk_product_inventory_version CHECK (inventory_version >= -1);
