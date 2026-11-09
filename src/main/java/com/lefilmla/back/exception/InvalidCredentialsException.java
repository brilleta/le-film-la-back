package com.lefilmla.back.exception;

/** Email inconnu ou mot de passe faux : volontairement indiscernables. */
public class InvalidCredentialsException extends RuntimeException {

	public InvalidCredentialsException() {
		super("Identifiants invalides");
	}
}
