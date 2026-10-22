package orderbook.api;

import java.util.Optional;

import orderbook.sim.ExchangeClient.Credentials;

/**
 * Who a caller is: API keys (and, when backed by the auth service, session tokens) to participant ids, plus display
 * labels. {@link ParticipantRegistry} keeps everything in memory (tests, the in-page build);
 * {@code orderbook.services.AuthServiceDirectory} delegates to the auth service and Postgres.
 */
public interface ParticipantDirectory {

    /** The reserved key of the anonymous browser session ("YOU", participant 1). */
    String userKey();

    /** Creates a participant with its first API key, labelled {@code <name>-<id>}. */
    Credentials register(String name);

    /** The participant behind an API key or session token; empty if unknown, revoked or expired. */
    Optional<Credentials> resolve(String apiKeyOrToken);

    /** Display label: {@code YOU}, the participant's label, {@code anon}, or {@code P<id>}. */
    String label(long participantId);
}
