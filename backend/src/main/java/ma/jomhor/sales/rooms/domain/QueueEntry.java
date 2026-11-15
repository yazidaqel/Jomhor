package ma.jomhor.sales.rooms.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A fan's place in a room (docs/03-schema.sql queue_entries): {@code rank} null until the tick ranks it,
 * {@code admittedAt} set by the first poll at its turn. The device and IP hashes and the draw key never leave the
 * database.
 */
public record QueueEntry(UUID id, UUID userId, Integer rank, Instant admittedAt) {}
