package com.lefilmla.back.exception;

public class UserNotFoundException extends RuntimeException {

	public UserNotFoundException() {
		super("Utilisateur introuvable");
	}
}
