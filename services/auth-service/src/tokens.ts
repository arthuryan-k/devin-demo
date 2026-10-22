import { createPrivateKey, createPublicKey, generateKeyPairSync, type KeyObject } from "node:crypto";
import { randomUUID } from "node:crypto";
import { calculateJwkThumbprint, exportJWK, jwtVerify, SignJWT, type JWK } from "jose";

export const ALGORITHM = "RS256";

/** Claims carried by every session token, in addition to iss/aud/sub/iat/exp/jti. */
export interface SessionClaims {
  userId: number;
  accountId: number;
  participantId: number;
  username: string;
  kind: "human" | "simulated";
}

export interface TokenOptions {
  issuer: string;
  audience: string;
  ttlSeconds: number;
  privateKeyPem?: string;
}

/** Signs RS256 session JWTs and publishes the public half as a JWKS, so other services verify locally. */
export class TokenIssuer {
  private constructor(
    private readonly privateKey: KeyObject,
    private readonly publicKey: KeyObject,
    readonly kid: string,
    readonly jwks: { keys: JWK[] },
    private readonly options: TokenOptions,
  ) {}

  static async create(options: TokenOptions): Promise<TokenIssuer> {
    let privateKey: KeyObject;
    if (options.privateKeyPem) {
      privateKey = createPrivateKey(options.privateKeyPem);
    } else {
      privateKey = generateKeyPairSync("rsa", { modulusLength: 2048 }).privateKey;
    }
    const publicKey = createPublicKey(privateKey);
    const jwk = await exportJWK(publicKey);
    const kid = await calculateJwkThumbprint(jwk);
    const published: JWK = { ...jwk, kid, alg: ALGORITHM, use: "sig" };
    return new TokenIssuer(privateKey, publicKey, kid, { keys: [published] }, options);
  }

  get ttlSeconds(): number {
    return this.options.ttlSeconds;
  }

  async issue(claims: SessionClaims): Promise<string> {
    const now = Math.floor(Date.now() / 1000);
    return new SignJWT({
      participantId: claims.participantId,
      accountId: claims.accountId,
      username: claims.username,
      kind: claims.kind,
    })
      .setProtectedHeader({ alg: ALGORITHM, kid: this.kid, typ: "JWT" })
      .setIssuer(this.options.issuer)
      .setAudience(this.options.audience)
      .setSubject(String(claims.userId))
      .setIssuedAt(now)
      .setExpirationTime(now + this.options.ttlSeconds)
      .setJti(randomUUID())
      .sign(this.privateKey);
  }

  /** Throws on a bad signature, wrong issuer/audience, or an expired token. */
  async verify(token: string): Promise<SessionClaims> {
    const { payload } = await jwtVerify(token, this.publicKey, {
      issuer: this.options.issuer,
      audience: this.options.audience,
      algorithms: [ALGORITHM],
    });
    const userId = Number(payload.sub);
    const { participantId, accountId, username, kind } = payload as Record<string, unknown>;
    if (!Number.isSafeInteger(userId) || typeof participantId !== "number" || typeof accountId !== "number"
        || typeof username !== "string" || (kind !== "human" && kind !== "simulated")) {
      throw new Error("malformed claims");
    }
    return { userId, participantId, accountId, username, kind };
  }
}
