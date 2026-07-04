CREATE TABLE user_app_settings (
    id                BIGSERIAL   PRIMARY KEY,
    username          VARCHAR(50) NOT NULL,
    monitored_apps    TEXT,
    include_keywords  TEXT,
    exclude_keywords  TEXT,
    blacklist_enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    whitelist_enabled BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at        TIMESTAMP,
    CONSTRAINT uq_app_settings_username UNIQUE (username),
    CONSTRAINT fk_app_settings_user FOREIGN KEY (username) REFERENCES users (username)
);
