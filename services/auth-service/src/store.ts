import type { Pool, PoolClient } from "pg";

export type UserKind = "human" | "simulated";

export interface AccountRecord {
  userId: number;
  username: string;
  kind: UserKind;
  accountId: number;
  participantId: number;
  label: string;
  startingCash: string;
}

export interface LoginRecord extends AccountRecord {
  passwordHash: string | null;
}

export interface ApiKeyRecord {
  id: number;
  prefix: string;
  label: string | null;
  createdAt: string;
  lastUsedAt: string | null;
  revokedAt: string | null;
}

export interface ApiKeyPrincipal extends AccountRecord {
  keyId: number;
}

/** Postgres unique_violation. */
export function isUniqueViolation(err: unknown): boolean {
  return typeof err === "object" && err !== null && (err as { code?: string }).code === "23505";
}

const ACCOUNT_COLUMNS = `u.id AS user_id, u.username, u.kind, a.id AS account_id, a.participant_id, a.label,
    a.starting_cash::text AS starting_cash`;

function toAccount(row: Record<string, unknown>): AccountRecord {
  return {
    userId: Number(row.user_id),
    username: String(row.username),
    kind: row.kind as UserKind,
    accountId: Number(row.account_id),
    participantId: Number(row.participant_id),
    label: String(row.label),
    startingCash: String(row.starting_cash),
  };
}

function toApiKey(row: Record<string, unknown>): ApiKeyRecord {
  const iso = (v: unknown) => (v == null ? null : new Date(v as string).toISOString());
  return {
    id: Number(row.id),
    prefix: String(row.key_prefix),
    label: (row.label as string | null) ?? null,
    createdAt: iso(row.created_at)!,
    lastUsedAt: iso(row.last_used_at),
    revokedAt: iso(row.revoked_at),
  };
}

export class Store {
  constructor(readonly pool: Pool) {}

  async transaction<T>(fn: (client: PoolClient) => Promise<T>): Promise<T> {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      const result = await fn(client);
      await client.query("COMMIT");
      return result;
    } catch (err) {
      await client.query("ROLLBACK").catch(() => {});
      throw err;
    } finally {
      client.release();
    }
  }

  /**
   * Creates a user and its one account in a single transaction. The account is granted the sum of
   * `reference_data.initial_cash_grant` over active symbols. `label` receives the allocated participant id.
   */
  async createAccount(input: {
    username: (participantId: number) => string;
    passwordHash: string | null;
    kind: UserKind;
    label: (participantId: number) => string;
  }, tx?: PoolClient): Promise<AccountRecord> {
    const run = async (client: PoolClient) => {
      const pid = Number((await client.query("SELECT nextval('participant_id_seq') AS id")).rows[0].id);
      const username = input.username(pid);
      const user = await client.query(
        "INSERT INTO users (username, password_hash, kind) VALUES ($1, $2, $3) RETURNING id",
        [username, input.passwordHash, input.kind],
      );
      const userId = Number(user.rows[0].id);
      await client.query(
        `INSERT INTO accounts (user_id, participant_id, label, starting_cash)
         SELECT $1, $2, $3, COALESCE(SUM(initial_cash_grant), 0) FROM reference_data WHERE active`,
        [userId, pid, input.label(pid)],
      );
      return (await this.accountByUserId(userId, client))!;
    };
    return tx ? run(tx) : this.transaction(run);
  }

  async accountByUserId(userId: number, client: Pool | PoolClient = this.pool): Promise<AccountRecord | null> {
    const res = await client.query(
      `SELECT ${ACCOUNT_COLUMNS} FROM users u JOIN accounts a ON a.user_id = u.id WHERE u.id = $1`,
      [userId],
    );
    return res.rows[0] ? toAccount(res.rows[0]) : null;
  }

  async accountByParticipantId(participantId: number): Promise<AccountRecord | null> {
    const res = await this.pool.query(
      `SELECT ${ACCOUNT_COLUMNS} FROM users u JOIN accounts a ON a.user_id = u.id WHERE a.participant_id = $1`,
      [participantId],
    );
    return res.rows[0] ? toAccount(res.rows[0]) : null;
  }

  async loginByUsername(username: string): Promise<LoginRecord | null> {
    const res = await this.pool.query(
      `SELECT ${ACCOUNT_COLUMNS}, u.password_hash FROM users u JOIN accounts a ON a.user_id = u.id
       WHERE lower(u.username) = lower($1)`,
      [username],
    );
    const row = res.rows[0];
    return row ? { ...toAccount(row), passwordHash: row.password_hash ?? null } : null;
  }

  async insertApiKey(accountId: number, hash: Buffer, prefix: string, label: string | null,
      client: Pool | PoolClient = this.pool): Promise<ApiKeyRecord> {
    const res = await client.query(
      `INSERT INTO api_keys (account_id, key_hash, key_prefix, label) VALUES ($1, $2, $3, $4)
       RETURNING id, key_prefix, label, created_at, last_used_at, revoked_at`,
      [accountId, hash, prefix, label],
    );
    return toApiKey(res.rows[0]);
  }

  async listApiKeys(accountId: number): Promise<ApiKeyRecord[]> {
    const res = await this.pool.query(
      `SELECT id, key_prefix, label, created_at, last_used_at, revoked_at FROM api_keys
       WHERE account_id = $1 ORDER BY id`,
      [accountId],
    );
    return res.rows.map(toApiKey);
  }

  /** True if an active key of this account was revoked. */
  async revokeApiKey(accountId: number, keyId: number): Promise<boolean> {
    const res = await this.pool.query(
      "UPDATE api_keys SET revoked_at = now() WHERE id = $1 AND account_id = $2 AND revoked_at IS NULL",
      [keyId, accountId],
    );
    return (res.rowCount ?? 0) > 0;
  }

  /** Resolves an unrevoked key; refreshes `last_used_at` at most once a minute per key. */
  async principalByKeyHash(hash: Buffer): Promise<ApiKeyPrincipal | null> {
    const res = await this.pool.query(
      `SELECT ${ACCOUNT_COLUMNS}, k.id AS key_id, k.last_used_at > now() - interval '1 minute' AS fresh
       FROM api_keys k JOIN accounts a ON a.id = k.account_id JOIN users u ON u.id = a.user_id
       WHERE k.key_hash = $1 AND k.revoked_at IS NULL`,
      [hash],
    );
    const row = res.rows[0];
    if (!row) {
      return null;
    }
    const keyId = Number(row.key_id);
    if (!row.fresh) {
      await this.pool.query("UPDATE api_keys SET last_used_at = now() WHERE id = $1", [keyId]);
    }
    return { ...toAccount(row), keyId };
  }
}
