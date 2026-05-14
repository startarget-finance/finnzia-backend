package com.finnza.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Respostas 400 com {@code mensagem} legível, em vez do JSON genérico do Spring
 * (evita só "Bad Request" no front quando o corpo JSON não casa com o DTO).
 */
@Slf4j
@RestControllerAdvice
public class GlobalApiExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getMostSpecificCause();
        String detail = cause != null && cause.getMessage() != null ? cause.getMessage() : ex.getMessage();
        log.warn("Corpo HTTP ilegível ou JSON incompatível com o DTO: {}", detail);

        String mensagem = "Não foi possível interpretar o corpo da requisição.";
        if (detail != null) {
            String d = detail.toLowerCase();
            if (d.contains("localdate") || d.contains("local time") || d.contains("date") || d.contains("deserialize")) {
                mensagem = "Formato de data inválido no JSON. Use ano-mês-dia (yyyy-MM-dd), por exemplo 2026-05-01.";
            } else if (d.contains("unexpected character") || d.contains("json")) {
                mensagem = "JSON malformado ou campo com tipo incompatível. Confira o corpo enviado à API.";
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("erro", true);
        body.put("mensagem", mensagem);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String first = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .orElse("Dados inválidos");
        log.warn("Validação de argumentos: {}", first);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("erro", true);
        body.put("mensagem", first);
        return ResponseEntity.badRequest().body(body);
    }
}
