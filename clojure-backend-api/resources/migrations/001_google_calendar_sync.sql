-- Em produção, a tabela `horarios` já existe. Criamos apenas quando a base é
-- nova (como staging), para que a aplicação possa ser validada isoladamente.
CREATE TABLE IF NOT EXISTS horarios (
  id BIGSERIAL PRIMARY KEY,
  psicologa_id VARCHAR(255) NOT NULL UNIQUE,
  nome VARCHAR(255) NOT NULL,
  senha_hash TEXT NOT NULL,
  horarios_disponiveis JSONB NOT NULL DEFAULT '{}'::jsonb,
  atualizado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- `psicologa_id` é texto para aceitar tanto IDs numéricos atuais quanto
-- futuros identificadores alfanuméricos.

CREATE TABLE IF NOT EXISTS app_admins (
  login VARCHAR(255) PRIMARY KEY,
  nome VARCHAR(255) NOT NULL,
  senha_hash TEXT NOT NULL,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS app_sessions (
  token_hash CHAR(64) PRIMARY KEY,
  conta_id VARCHAR(255) NOT NULL,
  papel VARCHAR(32) NOT NULL,
  expira_em TIMESTAMPTZ NOT NULL,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT app_sessions_papel_check CHECK (papel IN ('admin', 'psychologist'))
);

CREATE INDEX IF NOT EXISTS app_sessions_expira_em_idx ON app_sessions (expira_em);

CREATE TABLE IF NOT EXISTS google_oauth_states (
  state_hash CHAR(64) PRIMARY KEY,
  criado_por VARCHAR(255) NOT NULL,
  expira_em TIMESTAMPTZ NOT NULL,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS google_oauth_accounts (
  chave VARCHAR(64) PRIMARY KEY,
  refresh_token_criptografado TEXT NOT NULL,
  escopos TEXT,
  atualizado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS google_calendar_connections (
  psicologa_id VARCHAR(255) PRIMARY KEY,
  calendar_id VARCHAR(1024) NOT NULL UNIQUE,
  calendar_nome VARCHAR(1024),
  ultima_sincronizacao_em TIMESTAMPTZ,
  ultimo_erro TEXT,
  atualizado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS availability_auto_slots (
  calendar_id VARCHAR(1024) NOT NULL,
  google_event_id VARCHAR(1024) NOT NULL,
  psicologa_id VARCHAR(255) NOT NULL,
  inicia_em TIMESTAMPTZ NOT NULL,
  termina_em TIMESTAMPTZ NOT NULL,
  atualizado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (calendar_id, google_event_id)
);

CREATE INDEX IF NOT EXISTS availability_auto_slots_lookup_idx
  ON availability_auto_slots (psicologa_id, inicia_em);

CREATE TABLE IF NOT EXISTS availability_manual_overrides (
  id BIGSERIAL PRIMARY KEY,
  psicologa_id VARCHAR(255) NOT NULL,
  inicia_em TIMESTAMPTZ NOT NULL,
  termina_em TIMESTAMPTZ NOT NULL,
  disponivel BOOLEAN NOT NULL,
  observacao VARCHAR(500),
  criado_por VARCHAR(255) NOT NULL,
  criado_por_papel VARCHAR(32) NOT NULL,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT availability_manual_overrides_papel_check
    CHECK (criado_por_papel IN ('admin', 'psychologist')),
  CONSTRAINT availability_manual_overrides_periodo_check CHECK (termina_em > inicia_em)
);

CREATE INDEX IF NOT EXISTS availability_manual_overrides_lookup_idx
  ON availability_manual_overrides (psicologa_id, inicia_em);
