package com.finnza.event;

/**
 * Disparado após persistir um usuário novo; o envio de e-mail ocorre em
 * {@link com.finnza.listener.NovaContaConviteListener} após commit da transação.
 */
public record NovaContaConviteEvent(String email, String nome, String senhaProvisoria) {}
