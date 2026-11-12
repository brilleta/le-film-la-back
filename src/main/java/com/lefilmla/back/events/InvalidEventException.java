package com.lefilmla.back.events;

/** Message illisible ou incomplet : inutile de réessayer, il part directement en Dead Letter Topic. */
public class InvalidEventException extends RuntimeException {

	public InvalidEventException(String message, Throwable cause) {
		super(message, cause);
	}

	public InvalidEventException(String message) {
		super(message);
	}
}
