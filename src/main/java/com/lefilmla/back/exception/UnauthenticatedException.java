package com.lefilmla.back.exception;

public class UnauthenticatedException extends RuntimeException {

	public UnauthenticatedException() {
		super("Authentification requise");
	}
}
