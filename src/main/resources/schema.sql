CREATE TABLE IF NOT EXISTS accounts (
    user_id VARCHAR(36) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL,
    email VARCHAR(320) NOT NULL,
    email_normalized VARCHAR(320) NOT NULL UNIQUE,
    password_hash VARCHAR(200) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    role VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    protected_admin BOOLEAN NOT NULL DEFAULT FALSE,
    created_at BIGINT NOT NULL
);
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS enabled BOOLEAN DEFAULT TRUE NOT NULL;
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS protected_admin BOOLEAN DEFAULT FALSE NOT NULL;

CREATE TABLE IF NOT EXISTS organizations (
    organization_id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    created_by VARCHAR(36),
    created_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_organizations_created ON organizations(created_at);

CREATE TABLE IF NOT EXISTS documents (
    document_id VARCHAR(36) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL,
    uploaded_by VARCHAR(36) NOT NULL,
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(160) NOT NULL,
    size_bytes BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    failure_reason VARCHAR(2000),
    failure_code VARCHAR(100),
    failure_job_id VARCHAR(36),
    failure_attempt INT,
    failed_at BIGINT,
    content BLOB
);
ALTER TABLE documents ADD COLUMN IF NOT EXISTS failure_reason VARCHAR(2000);
ALTER TABLE documents ADD COLUMN IF NOT EXISTS failure_code VARCHAR(100);
ALTER TABLE documents ADD COLUMN IF NOT EXISTS failure_job_id VARCHAR(36);
ALTER TABLE documents ADD COLUMN IF NOT EXISTS failure_attempt INT;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS failed_at BIGINT;
CREATE INDEX IF NOT EXISTS idx_documents_org_created ON documents(organization_id, created_at);

CREATE TABLE IF NOT EXISTS extraction_jobs (
    job_id VARCHAR(36) PRIMARY KEY,
    document_id VARCHAR(36) NOT NULL,
    organization_id VARCHAR(36) NOT NULL,
    document_type VARCHAR(100) NOT NULL,
    output_format VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL,
    progress INT NOT NULL,
    attempt INT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    extraction_id VARCHAR(36),
    error_message VARCHAR(2000)
);
CREATE INDEX IF NOT EXISTS idx_jobs_org ON extraction_jobs(organization_id);
CREATE INDEX IF NOT EXISTS idx_jobs_document ON extraction_jobs(document_id);
CREATE TABLE IF NOT EXISTS extraction_failures (
    failure_id VARCHAR(36) PRIMARY KEY,
    job_id VARCHAR(36) NOT NULL,
    document_id VARCHAR(36) NOT NULL,
    organization_id VARCHAR(36) NOT NULL,
    attempt INT NOT NULL,
    error_code VARCHAR(100) NOT NULL,
    error_message VARCHAR(2000) NOT NULL,
    exception_type VARCHAR(300),
    occurred_at BIGINT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_failure_job_attempt ON extraction_failures(job_id, attempt);
CREATE INDEX IF NOT EXISTS idx_failure_document_time ON extraction_failures(document_id, occurred_at);
CREATE TABLE IF NOT EXISTS extraction_idempotency (
    scope_key VARCHAR(500) PRIMARY KEY,
    job_id VARCHAR(36) NOT NULL
);

CREATE TABLE IF NOT EXISTS extraction_results (
    extraction_id VARCHAR(36) PRIMARY KEY,
    job_id VARCHAR(36) NOT NULL UNIQUE,
    document_id VARCHAR(36) NOT NULL,
    organization_id VARCHAR(36) NOT NULL,
    document_type VARCHAR(100) NOT NULL,
    output_format VARCHAR(100) NOT NULL,
    data_json CLOB NOT NULL,
    review_status VARCHAR(32) NOT NULL,
    version INT NOT NULL,
    validation_json CLOB NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    approved_at BIGINT
);
CREATE INDEX IF NOT EXISTS idx_results_org ON extraction_results(organization_id);
CREATE INDEX IF NOT EXISTS idx_results_document ON extraction_results(document_id);

CREATE TABLE IF NOT EXISTS exports (
    export_id VARCHAR(36) PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL,
    document_ids_json CLOB NOT NULL,
    status VARCHAR(32) NOT NULL,
    filename VARCHAR(255) NOT NULL,
    content BLOB NOT NULL,
    created_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_exports_org ON exports(organization_id);

CREATE TABLE IF NOT EXISTS audit_events (
    event_id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    organization_id VARCHAR(36) NOT NULL,
    action VARCHAR(100) NOT NULL,
    resource VARCHAR(255) NOT NULL,
    response_status INT NOT NULL,
    created_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_events(created_at);

CREATE TABLE IF NOT EXISTS SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);
CREATE UNIQUE INDEX IF NOT EXISTS SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX IF NOT EXISTS SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX IF NOT EXISTS SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);
CREATE TABLE IF NOT EXISTS SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BLOB NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);
