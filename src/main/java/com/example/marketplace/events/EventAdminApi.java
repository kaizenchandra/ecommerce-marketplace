package com.example.marketplace.events;

import com.example.marketplace.shared.Store;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/events")
public class EventAdminApi {
    private final Store s;
    private final EventService events;

    public EventAdminApi(Store s, EventService events) {
        this.s = s;
        this.events = events;
    }

    @GetMapping("/failures")
    Object failures() {
        return s.db.queryForList("select * from event_failures order by created_at desc limit 100");
    }

    @PostMapping("/outbox/{id}/retry")
    Object retry(@PathVariable UUID id) {
        s.db.update(
                "update outbox_events set delivery_state='READY',attempts=0,next_attempt=now() where id=?"
                        + " and delivery_state='MANUAL_REVIEW'",
                id);
        s.audit("OUTBOX_RETRY", id);
        return Map.of("queued", true);
    }

    @PostMapping("/failures/{id}/replay")
    Object replay(@PathVariable UUID id) throws Exception {
        var failure = s.db.queryForMap("select * from event_failures where id=?", id);
        String raw = failure.get("payload").toString();
        var envelope = s.json.readValue(raw, EventService.Envelope.class);
        events.replay(raw, envelope.aggregateId().toString());
        s.audit("EVENT_REPLAY", id);
        return Map.of("published", true);
    }
}
