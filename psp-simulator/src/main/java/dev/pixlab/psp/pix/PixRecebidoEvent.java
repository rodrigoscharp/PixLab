package dev.pixlab.psp.pix;

import dev.pixlab.contracts.pix.Pix;

/** Publicado quando um Pix é liquidado; o dispatcher notifica o webhook da chave após o commit. */
public record PixRecebidoEvent(String chave, Pix pix) {}
