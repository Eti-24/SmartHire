CREATE TABLE IF NOT EXISTS account_recovery_codes (
    user_id BIGINT NOT NULL,
    code_hash BINARY(32) NOT NULL,
    PRIMARY KEY(user_id, code_hash),
    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
