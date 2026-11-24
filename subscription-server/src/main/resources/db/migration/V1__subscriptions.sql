CREATE TABLE subscription_account (
    account_id BINARY(32) PRIMARY KEY,
    group_id VARCHAR(96) NOT NULL,
    valid_until BIGINT NOT NULL,
    device_limit INT NOT NULL
) ENGINE=InnoDB;

CREATE TABLE subscription_device (
    device_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    account_id BINARY(32) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    key_digest BINARY(32) NOT NULL,
    public_key VARBINARY(256) NOT NULL,
    UNIQUE KEY account_key (account_id, key_digest),
    FOREIGN KEY (account_id) REFERENCES subscription_account(account_id)
) ENGINE=InnoDB;

CREATE TABLE subscription_pairing (
    user_code CHAR(12) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    secret_digest BINARY(32) NOT NULL UNIQUE,
    public_key VARBINARY(256) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    expires_at BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    device_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    INDEX pairing_expiry (expires_at)
) ENGINE=InnoDB;

CREATE TABLE subscription_challenge (
    nonce_digest BINARY(32) PRIMARY KEY,
    device_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at BIGINT NOT NULL,
    INDEX challenge_expiry (expires_at),
    FOREIGN KEY (device_id) REFERENCES subscription_device(device_id) ON DELETE CASCADE
) ENGINE=InnoDB;
