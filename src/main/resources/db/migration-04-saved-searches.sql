CREATE TABLE IF NOT EXISTS saved_job_searches (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    title VARCHAR(180) NOT NULL,
    category_id BIGINT NULL,
    location VARCHAR(180) NOT NULL,
    minimum_salary DECIMAL(12,2) NULL,
    maximum_salary DECIMAL(12,2) NULL,
    closing_from DATETIME(6) NULL,
    closing_before DATETIME(6) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    FOREIGN KEY(student_id) REFERENCES students(user_id) ON DELETE CASCADE,
    FOREIGN KEY(category_id) REFERENCES categories(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS saved_search_notices (
    notice_id CHAR(36) PRIMARY KEY,
    student_id BIGINT NOT NULL,
    job_id BIGINT NOT NULL,
    UNIQUE(student_id, job_id),
    FOREIGN KEY(notice_id) REFERENCES notifications(id) ON DELETE CASCADE,
    FOREIGN KEY(student_id) REFERENCES students(user_id) ON DELETE CASCADE,
    FOREIGN KEY(job_id) REFERENCES jobs(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
