-- Simulated banking schema for the Phase 1 demo. All data is fictional.
CREATE TABLE IF NOT EXISTS demo_customer (
    id            VARCHAR(32)  PRIMARY KEY,
    display_name  VARCHAR(100) NOT NULL,
    segment       VARCHAR(50),
    created_at    TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS bank_account (
    id             VARCHAR(32)   PRIMARY KEY,
    customer_id    VARCHAR(32)   NOT NULL,
    account_type   VARCHAR(20)   NOT NULL,
    account_name   VARCHAR(100)  NOT NULL,
    masked_number  VARCHAR(40)   NOT NULL,
    currency       VARCHAR(3)    NOT NULL,
    balance        DECIMAL(19,2) NOT NULL,
    status         VARCHAR(20)   NOT NULL,
    opened_on      DATE          NOT NULL,
    version        BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT fk_account_customer FOREIGN KEY (customer_id) REFERENCES demo_customer (id)
);

CREATE TABLE IF NOT EXISTS beneficiary (
    id              VARCHAR(32)  PRIMARY KEY,
    customer_id     VARCHAR(32)  NOT NULL,
    name            VARCHAR(100) NOT NULL,
    bank_name       VARCHAR(100) NOT NULL,
    masked_account  VARCHAR(40)  NOT NULL,
    relationship    VARCHAR(50),
    created_at      TIMESTAMP    NOT NULL,
    CONSTRAINT fk_beneficiary_customer FOREIGN KEY (customer_id) REFERENCES demo_customer (id)
);

CREATE TABLE IF NOT EXISTS bank_transaction (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id     VARCHAR(32)   NOT NULL,
    booked_at      TIMESTAMP     NOT NULL,
    direction      VARCHAR(10)   NOT NULL,
    category       VARCHAR(30)   NOT NULL,
    merchant       VARCHAR(100)  NOT NULL,
    description    VARCHAR(200),
    amount         DECIMAL(19,2) NOT NULL,
    currency       VARCHAR(3)    NOT NULL,
    balance_after  DECIMAL(19,2),
    reference      VARCHAR(64),
    CONSTRAINT fk_txn_account FOREIGN KEY (account_id) REFERENCES bank_account (id)
);

CREATE INDEX IF NOT EXISTS idx_txn_account_booked ON bank_transaction (account_id, booked_at);

CREATE TABLE IF NOT EXISTS transfer_draft (
    id                VARCHAR(64)   PRIMARY KEY,
    conversation_id   VARCHAR(64)   NOT NULL,
    customer_id       VARCHAR(32)   NOT NULL,
    from_account_id   VARCHAR(32)   NOT NULL,
    beneficiary_id    VARCHAR(32)   NOT NULL,
    beneficiary_name  VARCHAR(100)  NOT NULL,
    amount            DECIMAL(19,2) NOT NULL,
    currency          VARCHAR(3)    NOT NULL,
    memo              VARCHAR(140),
    status            VARCHAR(30)   NOT NULL,
    failure_code      VARCHAR(50),
    failure_reason    VARCHAR(300),
    created_at        TIMESTAMP     NOT NULL,
    expires_at        TIMESTAMP     NOT NULL,
    resolved_at       TIMESTAMP,
    transaction_id    BIGINT,
    version           BIGINT        NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_draft_conversation ON transfer_draft (conversation_id, created_at);

CREATE TABLE IF NOT EXISTS payment_audit (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    draft_id          VARCHAR(64),
    conversation_id   VARCHAR(64),
    customer_id       VARCHAR(32)   NOT NULL,
    action            VARCHAR(30)   NOT NULL,
    outcome           VARCHAR(20)   NOT NULL,
    beneficiary_name  VARCHAR(100),
    amount            DECIMAL(19,2),
    code              VARCHAR(200),
    detail            VARCHAR(500),
    created_at        TIMESTAMP     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_conversation ON payment_audit (conversation_id, created_at);
