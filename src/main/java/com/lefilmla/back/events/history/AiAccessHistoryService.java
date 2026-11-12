package com.lefilmla.back.events.history;

import com.lefilmla.back.dto.AiAccessHistoryResponse;
import com.lefilmla.back.events.AiAccessEvent;
import com.lefilmla.back.exception.UserNotFoundException;
import com.lefilmla.back.repository.UserRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiAccessHistoryService {

	private static final Logger log = LoggerFactory.getLogger(AiAccessHistoryService.class);

	private final AiAccessHistoryRepository repository;

	private final UserRepository userRepository;

	private final Clock clock;

	public AiAccessHistoryService(AiAccessHistoryRepository repository, UserRepository userRepository, Clock clock) {
		this.repository = repository;
		this.userRepository = userRepository;
		this.clock = clock;
	}

	/** @return false si l'événement avait déjà été enregistré (livraison « au moins une fois ») */
	@Transactional
	public boolean record(AiAccessEvent event) {
		var decidedBy = event.decidedBy();
		int inserted = repository.insertIfAbsent(event.eventId(), event.userId(), event.type().name(),
				event.previousStatus().name(), event.newStatus().name(), decidedBy == null ? null : decidedBy.id(),
				decidedBy == null ? null : decidedBy.username(), event.occurredAt(), clock.instant());
		if (inserted == 0) {
			log.info("Événement {} déjà enregistré, ignoré", event.eventId());
			return false;
		}
		return true;
	}

	@Transactional(readOnly = true)
	public List<AiAccessHistoryResponse> history(UUID userId) {
		if (!userRepository.existsById(userId)) {
			throw new UserNotFoundException();
		}
		return repository.findByUserIdOrderByOccurredAtDescIdDesc(userId)
			.stream()
			.map(AiAccessHistoryResponse::from)
			.toList();
	}
}
