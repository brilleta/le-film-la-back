package com.lefilmla.back.stats;

/** Le flux Kafka Streams n'est pas prêt (démarrage, rééquilibrage, broker absent) : réponse 503. */
public class StatsUnavailableException extends RuntimeException {

	public StatsUnavailableException() {
		super("Statistiques temporairement indisponibles");
	}
}
