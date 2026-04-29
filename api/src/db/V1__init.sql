-- V1__init.sql
-- FamilyDNS initial schema

CREATE TABLE users (
  id            BIGSERIAL PRIMARY KEY,
  username      TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  role          TEXT NOT NULL DEFAULT 'readonly',   -- admin | readonly
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE profiles (
  id                  BIGSERIAL PRIMARY KEY,
  name                TEXT NOT NULL,
  blocked_categories  TEXT[] NOT NULL DEFAULT '{}',
  extra_blocked       TEXT[] NOT NULL DEFAULT '{}',
  extra_allowed       TEXT[] NOT NULL DEFAULT '{}',
  paused              BOOLEAN NOT NULL DEFAULT FALSE,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE schedules (
  id           BIGSERIAL PRIMARY KEY,
  profile_id   BIGINT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  name         TEXT NOT NULL,
  days         TEXT[] NOT NULL,   -- mon,tue,wed,thu,fri,sat,sun
  block_from   TEXT NOT NULL,     -- HH:mm
  block_until  TEXT NOT NULL,     -- HH:mm
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_schedules_profile ON schedules(profile_id);

CREATE TABLE devices (
  id            BIGSERIAL PRIMARY KEY,
  mac           TEXT NOT NULL UNIQUE,   -- aa:bb:cc:dd:ee:ff lowercase
  name          TEXT NOT NULL,
  profile_id    BIGINT REFERENCES profiles(id) ON DELETE SET NULL,
  last_seen_ip  TEXT,
  last_seen_at  TIMESTAMPTZ,
  location      TEXT,                   -- e.g. 'home', 'vacation'
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_devices_mac ON devices(mac);

CREATE TABLE blocklist_domains (
  id        BIGSERIAL PRIMARY KEY,
  domain    TEXT NOT NULL,
  category  TEXT NOT NULL,
  UNIQUE(domain, category)
);

CREATE INDEX idx_blocklist_domain   ON blocklist_domains(domain);
CREATE INDEX idx_blocklist_category ON blocklist_domains(category);

CREATE TABLE query_logs (
  id           BIGSERIAL PRIMARY KEY,
  mac          TEXT,
  device_name  TEXT,
  profile_id   BIGINT,
  profile_name TEXT,
  domain       TEXT NOT NULL,
  qtype        INT NOT NULL DEFAULT 1,
  blocked      BOOLEAN NOT NULL,
  reason       TEXT NOT NULL,
  location     TEXT,
  ts           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_query_logs_ts      ON query_logs(ts DESC);
CREATE INDEX idx_query_logs_mac     ON query_logs(mac);
CREATE INDEX idx_query_logs_blocked ON query_logs(blocked);
CREATE INDEX idx_query_logs_domain  ON query_logs(domain);

-- Seed default profiles
INSERT INTO profiles (name, blocked_categories, paused) VALUES
  ('Kids',   ARRAY['adult','gambling','social_media','proxy'], FALSE),
  ('Adults', ARRAY[]::TEXT[],                                  FALSE);

-- Seed default admin user (password: changeme — force change on first login)
-- bcrypt hash of 'changeme'
INSERT INTO users (username, password_hash, role) VALUES
  ('admin', '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewdBPj4J/HS.iSEu', 'admin');
