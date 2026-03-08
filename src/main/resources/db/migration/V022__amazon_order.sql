-- Amazon order history table: stores all imported Amazon orders per user
CREATE TABLE amazon_order (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id              BIGINT NOT NULL REFERENCES app_user(id),
    order_id             VARCHAR(50) NOT NULL,
    asin                 VARCHAR(20) NOT NULL DEFAULT '',
    product_name         VARCHAR(1000) NOT NULL,
    product_name_lower   VARCHAR(1000) NOT NULL,
    order_date           TIMESTAMP,
    ship_date            TIMESTAMP,
    order_status         VARCHAR(20),
    product_condition    VARCHAR(20),
    unit_price           DECIMAL(10,2),
    unit_price_tax       DECIMAL(10,2),
    total_amount         DECIMAL(10,2),
    total_discounts      DECIMAL(10,2),
    quantity             INTEGER DEFAULT 1,
    currency             VARCHAR(10),
    website              VARCHAR(100),
    linked_media_item_id BIGINT REFERENCES media_item(id),
    linked_at            TIMESTAMP,
    imported_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_amazon_order_dedup ON amazon_order(user_id, order_id, asin);
CREATE INDEX idx_amazon_order_user ON amazon_order(user_id);
CREATE INDEX idx_amazon_order_name ON amazon_order(product_name_lower);
CREATE INDEX idx_amazon_order_date ON amazon_order(order_date);

-- Wipe existing purchase data so Amazon import is the sole source
UPDATE media_item SET purchase_place = NULL, purchase_date = NULL,
    purchase_price = NULL, amazon_order_id = NULL;
