package com.lefilmla.back.events;

import com.lefilmla.back.config.KafkaTopics;
import com.lefilmla.back.entity.AiAccess;
import com.lefilmla.back.entity.User;
import com.lefilmla.back.events.outbox.Outbox;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Point d'entrée métier : construit l'événement et le dépose dans l'outbox, dans la transaction appelante. */
@Component
public class AiAccessEventPublisher {

	private final Outbox outbox;

	public AiAccessEventPublisher(Outbox outbox) {
		this.outbox = outbox;
	}

	public void publish(AiAccessEventType type, User user, AiAccess previousStatus, User decidedBy, Instant now) {
		AiAccessEvent event = AiAccessEvent.of(type, user, previousStatus, decidedBy, now);
		outbox.append(KafkaTopics.AI_ACCESS_EVENTS, user.getId().toString(), event.eventId(), type.name(), event,
				now);
	}
}
