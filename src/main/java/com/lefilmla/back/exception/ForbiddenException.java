package com.lefilmla.back.exception;

/** 403 métier avec un code explicite (ex. AI_ACCESS_REQUIRED, SELF_ACCESS_CHANGE). */
public class ForbiddenException extends RuntimeException {

	private final String code;

	public ForbiddenException(String code, String message) {
		super(message);
		this.code = code;
	}

	public String code() {
		return code;
	}
}
