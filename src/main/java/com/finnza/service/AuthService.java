package com.finnza.service;

import com.finnza.domain.entity.Usuario;
import com.finnza.dto.request.ForgotPasswordRequest;
import com.finnza.dto.request.LoginRequest;
import com.finnza.dto.request.ResetPasswordRequest;
import com.finnza.dto.response.LoginResponse;
import com.finnza.dto.response.UsuarioDTO;
import com.finnza.repository.UsuarioRepository;
import com.finnza.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Serviço de autenticação
 */
@Service
@Transactional
public class AuthService {

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private UserDetailsService userDetailsService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * Realiza o login do usuário
     */
    public LoginResponse login(LoginRequest request) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.getEmail(),
                            request.getSenha()
                    )
            );

            SecurityContextHolder.getContext().setAuthentication(authentication);

            UserDetails userDetails = userDetailsService.loadUserByUsername(request.getEmail());
            String token = tokenProvider.generateToken(userDetails);

            Usuario usuario = usuarioRepository.findByEmailWithPermissoes(request.getEmail())
                    .orElseThrow(() -> new BadCredentialsException("Usuário não encontrado"));

            // Atualiza último acesso
            usuario.atualizarUltimoAcesso();
            usuarioRepository.save(usuario);

            return LoginResponse.builder()
                    .token(token)
                    .usuario(UsuarioDTO.fromEntity(usuario))
                    .build();

        } catch (Exception e) {
            throw new BadCredentialsException("Email ou senha inválidos");
        }
    }

    /**
     * Solicita recuperação de senha
     */
    public void solicitarRecuperacaoSenha(ForgotPasswordRequest request) {
        Usuario usuario = usuarioRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("Email não encontrado"));

        // Gerar token de recuperação (válido por 1 hora)
        String token = UUID.randomUUID().toString();
        usuario.setTokenResetSenha(token);
        usuario.setTokenResetSenhaExpiracao(LocalDateTime.now().plusHours(1));
        usuarioRepository.save(usuario);

        // TODO: Enviar email com o token
        // Por enquanto, apenas salva o token no banco
        // Em produção, enviar email com link: /reset-password?token={token}
    }

    /**
     * Redefine a senha usando o token de recuperação
     */
    public void redefinirSenha(ResetPasswordRequest request) {
        Usuario usuario = usuarioRepository.findByTokenResetSenha(request.getToken())
                .orElseThrow(() -> new RuntimeException("Token inválido ou expirado"));

        // Verificar se o token não expirou
        if (usuario.getTokenResetSenhaExpiracao() == null ||
            usuario.getTokenResetSenhaExpiracao().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Token expirado");
        }

        // Atualizar senha e limpar token
        usuario.setSenha(passwordEncoder.encode(request.getNovaSenha()));
        usuario.setTokenResetSenha(null);
        usuario.setTokenResetSenhaExpiracao(null);
        usuarioRepository.save(usuario);
    }
}

