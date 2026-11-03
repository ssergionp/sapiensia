package br.com.sergio.sapiensia.customer;

/** The wrong password that used the last attempt: the link was just deleted. Later uses of it are plain invalid. */
public class WrongPasswordLimitReachedException extends InvalidConfirmationTokenException {}
