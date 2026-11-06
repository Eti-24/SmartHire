CREATE TABLE IF NOT EXISTS schema_migrations (
    version INT PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    credential_version BIGINT NOT NULL DEFAULT 1,
    role ENUM('STUDENT', 'COMPANY', 'ADMIN') NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    display_name VARCHAR(120) NOT NULL,
    phone VARCHAR(40) NOT NULL DEFAULT '',
    affiliation VARCHAR(180) NOT NULL DEFAULT '',
    about VARCHAR(2000) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_users_email UNIQUE (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS students (
    user_id BIGINT PRIMARY KEY,
    CONSTRAINT fk_students_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS companies (
    user_id BIGINT PRIMARY KEY,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_companies_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS job_catalog_lock (
    id INT PRIMARY KEY
) ENGINE=InnoDB;
INSERT IGNORE INTO job_catalog_lock(id) VALUES (1);
CREATE TABLE IF NOT EXISTS categories (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL UNIQUE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS skills (
    id VARCHAR(80) PRIMARY KEY,
    name VARCHAR(180) NOT NULL,
    type ENUM('TECHNICAL', 'SOFT') NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS skill_aliases (
    skill_id VARCHAR(80) NOT NULL,
    phrase VARCHAR(180) NOT NULL,
    PRIMARY KEY(skill_id, phrase),
    FOREIGN KEY(skill_id) REFERENCES skills(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS jobs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    vacancy_count INT NOT NULL DEFAULT 1,
    application_deadline_at DATETIME(6) NULL,
    application_deadline_zone VARCHAR(80) NULL,
    CONSTRAINT job_positive_vacancy_count CHECK (vacancy_count > 0),
    CONSTRAINT job_deadline_has_zone CHECK ((application_deadline_at IS NULL) = (application_deadline_zone IS NULL)),
    company_id BIGINT NOT NULL,
    title VARCHAR(160) NOT NULL,
    description TEXT NOT NULL,
    category_id BIGINT NOT NULL,
    location VARCHAR(180) NOT NULL,
    salary_min DECIMAL(12,2) NOT NULL,
    salary_max DECIMAL(12,2) NOT NULL,
    status ENUM('PENDING','APPROVED','REJECTED','CLOSED') NOT NULL DEFAULT 'PENDING',
    decision VARCHAR(1000) NOT NULL DEFAULT '',
    FOREIGN KEY(company_id) REFERENCES companies(user_id),
    FOREIGN KEY(category_id) REFERENCES categories(id),
    INDEX job_visibility(status, category_id, company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS saved_jobs (
    student_id BIGINT NOT NULL,
    job_id BIGINT NOT NULL,
    saved_title VARCHAR(160) NOT NULL,
    saved_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(student_id, job_id),
    INDEX saved_job_posting(job_id),
    FOREIGN KEY(student_id) REFERENCES students(user_id) ON DELETE CASCADE,
    FOREIGN KEY(job_id) REFERENCES jobs(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS job_requirements (
    job_id BIGINT NOT NULL,
    ordinal INT NOT NULL,
    kind VARCHAR(30) NOT NULL,
    value VARCHAR(180) NOT NULL,
    months INT NOT NULL,
    priority ENUM('REQUIRED','OPTIONAL') NOT NULL,
    mandatory BOOLEAN NOT NULL,
    PRIMARY KEY(job_id, ordinal),
    FOREIGN KEY(job_id) REFERENCES jobs(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS managed_documents (
    id CHAR(36) PRIMARY KEY,
    format ENUM('PDF','DOCX') NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    byte_size BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    extracted_text MEDIUMTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS cv_versions (
    id CHAR(36) PRIMARY KEY,
    student_id BIGINT NOT NULL,
    document_id CHAR(36) NOT NULL,
    reviewed_at TIMESTAMP(6) NOT NULL,
    degree VARCHAR(20),
    degree_field VARCHAR(180) NOT NULL,
    UNIQUE(student_id, id),
    FOREIGN KEY(student_id) REFERENCES students(user_id),
    FOREIGN KEY(document_id) REFERENCES managed_documents(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS cv_current (
    student_id BIGINT PRIMARY KEY,
    version_id CHAR(36) NOT NULL,
    FOREIGN KEY(student_id, version_id) REFERENCES cv_versions(student_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS cv_skills (
    version_id CHAR(36) NOT NULL,
    ordinal INT NOT NULL,
    skill_id VARCHAR(80) NOT NULL,
    PRIMARY KEY(version_id, ordinal),
    UNIQUE(version_id, skill_id),
    FOREIGN KEY(version_id) REFERENCES cv_versions(id),
    FOREIGN KEY(skill_id) REFERENCES skills(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS cv_evidence (
    version_id CHAR(36) NOT NULL,
    kind ENUM('PROJECT','EXPERIENCE') NOT NULL,
    ordinal INT NOT NULL,
    evidence VARCHAR(2000) NOT NULL,
    start_month CHAR(7),
    end_month CHAR(7),
    ongoing BOOLEAN NOT NULL,
    PRIMARY KEY(version_id, kind, ordinal),
    FOREIGN KEY(version_id) REFERENCES cv_versions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS cv_evidence_skills (
    version_id CHAR(36) NOT NULL,
    kind ENUM('PROJECT','EXPERIENCE') NOT NULL,
    evidence_ordinal INT NOT NULL,
    ordinal INT NOT NULL,
    skill_id VARCHAR(80) NOT NULL,
    PRIMARY KEY(version_id, kind, evidence_ordinal, ordinal),
    UNIQUE(version_id, kind, evidence_ordinal, skill_id),
    FOREIGN KEY(version_id, kind, evidence_ordinal) REFERENCES cv_evidence(version_id, kind, ordinal),
    FOREIGN KEY(skill_id) REFERENCES skills(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS cv_certifications (
    version_id CHAR(36) NOT NULL,
    ordinal INT NOT NULL,
    name VARCHAR(180) NOT NULL,
    PRIMARY KEY(version_id, ordinal),
    FOREIGN KEY(version_id) REFERENCES cv_versions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS applications (
    id CHAR(36) PRIMARY KEY,
    student_id BIGINT NOT NULL,
    job_id BIGINT NOT NULL,
    cv_version_id CHAR(36) NOT NULL,
    submitted_at TIMESTAMP(6) NOT NULL,
    hired_at TIMESTAMP(6) NULL,
    status ENUM('APPLIED','SHORTLISTED','HIRED','REJECTED','WITHDRAWN') NOT NULL,
    student_name VARCHAR(120) NOT NULL,
    student_email VARCHAR(254) NOT NULL,
    student_phone VARCHAR(40) NOT NULL,
    student_affiliation VARCHAR(180) NOT NULL,
    student_about VARCHAR(2000) NOT NULL,
    original_policy VARCHAR(80) NOT NULL,
    original_score DOUBLE NOT NULL,
    original_explanation MEDIUMTEXT NOT NULL,
    UNIQUE(student_id, job_id),
    FOREIGN KEY(student_id, cv_version_id) REFERENCES cv_versions(student_id, id),
    FOREIGN KEY(job_id) REFERENCES jobs(id),
    INDEX application_job_status(job_id, status, submitted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS interview_events (
    application_id CHAR(36) NOT NULL,
    revision INT NOT NULL,
    status ENUM('PROPOSED','CONFIRMED','DECLINED','RESCHEDULE_REQUESTED','CANCELLED') NOT NULL,
    starts_utc DATETIME(6) NOT NULL,
    ends_utc DATETIME(6) NOT NULL,
    display_zone VARCHAR(80) NOT NULL,
    location VARCHAR(500) NOT NULL,
    changed_utc DATETIME(6) NOT NULL,
    actor VARCHAR(12) NOT NULL,
    reason VARCHAR(100) NOT NULL,
    PRIMARY KEY(application_id, revision),
    FOREIGN KEY(application_id) REFERENCES applications(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS application_notes (
    application_id CHAR(36) PRIMARY KEY,
    revision INT NOT NULL,
    notes VARCHAR(4000) NOT NULL,
    updated_utc DATETIME(6) NOT NULL,
    FOREIGN KEY(application_id) REFERENCES applications(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS interview_feedback (
    application_id CHAR(36) PRIMARY KEY,
    interview_revision INT NOT NULL,
    rubric_version INT NOT NULL,
    technical TINYINT NULL,
    communication TINYINT NULL,
    role_fit TINYINT NULL,
    comments VARCHAR(4000) NOT NULL,
    FOREIGN KEY(application_id) REFERENCES application_notes(application_id) ON DELETE CASCADE,
    FOREIGN KEY(application_id, interview_revision) REFERENCES interview_events(application_id, revision) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS scoring_policies (
    version VARCHAR(80) PRIMARY KEY,
    skills INT NOT NULL,
    education INT NOT NULL,
    experience INT NOT NULL,
    projects INT NOT NULL,
    certifications INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO scoring_policies(version,skills,education,experience,projects,certifications) VALUES ('default-v1',40,20,15,15,10);
CREATE TABLE IF NOT EXISTS scoring_policy_current (
    id INT PRIMARY KEY,
    version VARCHAR(80) NOT NULL,
    FOREIGN KEY(version) REFERENCES scoring_policies(version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO scoring_policy_current(id,version) VALUES (1,'default-v1');
CREATE TABLE IF NOT EXISTS job_circular_versions (
    job_id BIGINT NOT NULL,
    document_id CHAR(36) NOT NULL,
    PRIMARY KEY(job_id, document_id),
    FOREIGN KEY(job_id) REFERENCES jobs(id),
    FOREIGN KEY(document_id) REFERENCES managed_documents(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS job_circulars (
    job_id BIGINT PRIMARY KEY,
    document_id CHAR(36) NOT NULL,
    FOREIGN KEY(job_id) REFERENCES jobs(id),
    FOREIGN KEY(document_id) REFERENCES managed_documents(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS notifications (
    id CHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    message VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    read_at TIMESTAMP(6) NULL,
    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX notification_inbox(user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS interview_notices (
    notice_id CHAR(36) PRIMARY KEY,
    application_id CHAR(36) NOT NULL,
    FOREIGN KEY(notice_id) REFERENCES notifications(id) ON DELETE CASCADE,
    FOREIGN KEY(application_id) REFERENCES applications(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS admin_activity (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    admin_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    target_kind VARCHAR(16) NOT NULL,
    target_id VARCHAR(36) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    record_count BIGINT NULL,
    operation_id CHAR(36) NULL,
    INDEX admin_activity_action_time(action,event_at),
    INDEX admin_activity_target(target_id,id),
    INDEX admin_activity_operation(operation_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS cv_approvals (
    version_id CHAR(36) PRIMARY KEY,
    status ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING',
    reason VARCHAR(1000) NOT NULL DEFAULT '',
    reviewer_id BIGINT NULL,
    decided_at TIMESTAMP(6) NULL,
    FOREIGN KEY(version_id) REFERENCES cv_versions(id) ON DELETE CASCADE,
    FOREIGN KEY(reviewer_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
