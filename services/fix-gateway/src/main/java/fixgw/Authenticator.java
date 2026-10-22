package fixgw;

/** Checks a Logon's Username(553)/Password(554). Password is the participant's API key. */
public interface Authenticator {
    /** @return the participant id, or -1 if the credentials are no good */
    long authenticate(String username, String password);
}
