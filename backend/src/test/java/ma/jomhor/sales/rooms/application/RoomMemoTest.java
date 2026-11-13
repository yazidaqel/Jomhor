package ma.jomhor.sales.rooms.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import ma.jomhor.IntegrationTest;
import ma.jomhor.sales.rooms.domain.WaitingRoom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** docs/04-api.md §Waiting room Memo: one statement per TTL whatever the readers; ENDED rooms are not in it. */
class RoomMemoTest extends IntegrationTest {

    @Autowired
    WaitingRooms rooms;

    private int roomOf(UUID org, String status) {
        UUID venue = jdbc.sql("insert into venues (organizer_id, name, capacity) values (?, 'Stade', 100) returning id")
                .param(org).query(UUID.class).single();
        int event = jdbc.sql("""
                        insert into events (organizer_id, venue_id, title, starts_at, sale_starts_at, event_key_enc)
                        values (?, ?, 'Match', ?, ?, ?) returning id""")
                .params(org, venue, Instant.parse("2099-05-01T19:00:00Z").atOffset(ZoneOffset.UTC),
                        Instant.parse("2099-04-01T10:00:00Z").atOffset(ZoneOffset.UTC), new byte[32])
                .query(Integer.class).single();
        jdbc.sql("insert into waiting_rooms (organizer_id, event_id, status) values (?, ?, ?::room_status)")
                .params(org, event, status).update();
        return event;
    }

    @Test
    void oneQueryPerTtlWhateverTheCallCountAndEndedRoomsAbsent() {
        UUID org = organizer("Club " + UUID.randomUUID());
        int scheduled = roomOf(org, "SCHEDULED");
        int open = roomOf(org, "OPEN");
        int ended = roomOf(org, "ENDED");
        WaitingRooms counted = mock(WaitingRooms.class, delegatesTo(rooms));
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2099-04-01T10:00:00Z"));
        RoomMemo memo = new RoomMemo(counted, now::get, Duration.ofSeconds(1));

        for (int i = 0; i < 1_000; i++) {
            memo.get(open);
        }
        verify(counted, times(1)).live();
        assertThat(memo.get(scheduled)).map(WaitingRoom::status).contains(WaitingRoom.Status.SCHEDULED);
        assertThat(memo.get(open)).map(WaitingRoom::opensAt).contains(Instant.parse("2099-04-01T09:30:00Z"));
        assertThat(memo.get(ended)).isEmpty();
        assertThat(memo.get(-1)).isEmpty();

        now.set(now.get().plusMillis(999));
        memo.get(open);
        verify(counted, times(1)).live();

        now.set(now.get().plusMillis(1));
        memo.get(open);
        memo.get(scheduled);
        verify(counted, times(2)).live();

        memo.invalidate();
        memo.get(open);
        verify(counted, times(3)).live();
    }
}
