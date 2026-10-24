CREATE TABLE payments (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    order_number        VARCHAR(32)  NOT NULL,
    gateway             VARCHAR(16)  NOT NULL,
    amount_minor        BIGINT       NOT NULL,
    currency            VARCHAR(3)   NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    payment_link        VARCHAR(512) NULL,
    customer_name       VARCHAR(255) NULL,
    customer_email      VARCHAR(255) NULL,
    customer_phone      VARCHAR(32)  NULL,
    gateway_payment_id  VARCHAR(64)  NULL,
    payment_method      VARCHAR(32)  NULL,
    receipt_number      VARCHAR(40)  NULL,
    paid_at             DATETIME(6)  NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    version             BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payments_order_number (order_number),
    UNIQUE KEY uk_payments_receipt_number (receipt_number)
);

-- Every event received for a payment, including repeats of one the payment already reflects.
CREATE TABLE payment_event (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    payment_id          BIGINT       NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    source              VARCHAR(16)  NOT NULL,
    gateway_payment_id  VARCHAR(64)  NULL,
    detail              VARCHAR(255) NULL,
    created_at          DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_payment_event_payment (payment_id),
    CONSTRAINT fk_payment_event_payment FOREIGN KEY (payment_id) REFERENCES payments (id)
);
