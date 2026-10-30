package com.lefilmla.back.exception;

public class UpstreamException extends RuntimeException {

	public UpstreamException(String message, Throwable cause) {
		super(message, cause);
	}
}
