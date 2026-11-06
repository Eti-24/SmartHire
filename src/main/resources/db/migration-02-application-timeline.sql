CREATE TABLE IF NOT EXISTS application_status_events (
    application_id CHAR(36) NOT NULL,
    sequence_no INT NOT NULL,
    previous_status ENUM('APPLIED','SHORTLISTED','HIRED','REJECTED','WITHDRAWN') NOT NULL,
    new_status ENUM('APPLIED','SHORTLISTED','HIRED','REJECTED','WITHDRAWN') NOT NULL,
    actor_role ENUM('STUDENT','COMPANY') NOT NULL,
    changed_utc DATETIME(6) NOT NULL,
    PRIMARY KEY(application_id, sequence_no),
    FOREIGN KEY(application_id) REFERENCES applications(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
