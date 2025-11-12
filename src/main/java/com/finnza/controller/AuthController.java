package com.finnza.controller;

import com.finnza.dto.request.ForgotPasswordRequest;
import com.finnza.dto.request.LoginRequest;
import com.finnza.dto.request.ResetPasswordRequest;
import com.finnza.dto.response.LoginResponse;
import com.finnza.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controller para autenticação
 */
@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    @Autowired
    private AuthService authService;

    /**
     * Endpoint de login
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Solicita recuperação de senha
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.solicitarRecuperacaoSenha(request);
        return ResponseEntity.ok().build();
    }

    /**
     * Redefine a senha usando o token
     */
    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.redefinirSenha(request);
        return ResponseEntity.ok().build();
    }
}

