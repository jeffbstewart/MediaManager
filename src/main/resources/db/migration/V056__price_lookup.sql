CREATE TABLE price_lookup (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    media_item_id BIGINT NOT NULL,
    lookup_key_type VARCHAR(16) NOT NULL,
    lookup_key VARCHAR(64) NOT NULL,
    price_new_current DECIMAL(10,2),
    price_new_avg_30d DECIMAL(10,2),
    price_new_avg_90d DECIMAL(10,2),
    price_amazon_current DECIMAL(10,2),
    price_used_current DECIMAL(10,2),
    offer_count_new INT,
    offer_count_used INT,
    keepa_asin VARCHAR(16),
    selected_price DECIMAL(10,2),
    looked_up_at TIMESTAMP NOT NULL,
    raw_json TEXT,
    CONSTRAINT fk_price_lookup_media_item FOREIGN KEY (media_item_id) REFERENCES media_item(id)
);

CREATE INDEX idx_price_lookup_media_item ON price_lookup(media_item_id);
CREATE INDEX idx_price_lookup_looked_up ON price_lookup(looked_up_at);
