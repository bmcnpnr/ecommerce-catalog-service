CREATE TABLE catalog_entries (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL UNIQUE,
    product_sku VARCHAR(100) NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    description TEXT,
    price DECIMAL(19,2) NOT NULL,
    brand VARCHAR(100),
    category_id BIGINT,
    category_name VARCHAR(100),
    featured BOOLEAN NOT NULL DEFAULT FALSE,
    stock_status VARCHAR(50) NOT NULL DEFAULT 'AVAILABLE',
    image_url VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_catalog_entries_category_id ON catalog_entries(category_id);
CREATE INDEX idx_catalog_entries_featured ON catalog_entries(featured);
CREATE INDEX idx_catalog_entries_stock_status ON catalog_entries(stock_status);
