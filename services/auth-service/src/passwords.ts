import bcrypt from "bcryptjs";

/** bcrypt only uses the first 72 bytes of a password. */
export const MAX_PASSWORD_BYTES = 72;
export const MIN_PASSWORD_LENGTH = 8;

export class Passwords {
  private readonly dummyHash: string;

  constructor(private readonly cost: number) {
    this.dummyHash = bcrypt.hashSync("not-a-real-password", cost);
  }

  hash(password: string): Promise<string> {
    return bcrypt.hash(password, this.cost);
  }

  /** Compares against a dummy hash when there is no user, so unknown usernames take as long as wrong passwords. */
  verify(password: string, hash: string | null | undefined): Promise<boolean> {
    return bcrypt.compare(password, hash ?? this.dummyHash).then((ok) => ok && hash != null);
  }
}
