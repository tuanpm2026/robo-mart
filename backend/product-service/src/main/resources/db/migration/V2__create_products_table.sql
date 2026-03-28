CREATE TABLE products (
    id              BIGSERIAL PRIMARY KEY,
    sku             VARCHAR(100) NOT NULL UNIQUE,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    price           NUMERIC(10, 2) NOT NULL,
    category_id     BIGINT NOT NULL REFERENCES categories(id),
    rating          NUMERIC(3, 2) DEFAULT 0.00,
    brand           VARCHAR(255),
    stock_quantity  INTEGER NOT NULL DEFAULT 0,
    version         INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_products_category_id ON products(category_id);
CREATE INDEX idx_products_sku ON products(sku);
CREATE INDEX idx_products_created_at ON products(created_at DESC);
