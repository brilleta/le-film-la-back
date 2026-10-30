package com.lefilmla.back.exception;

public class TmdbNotFoundException extends RuntimeException {

	public TmdbNotFoundException() {
		super("Contenu introuvable");
	}
}
