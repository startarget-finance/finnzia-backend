package com.finnza.service;

import com.finnza.domain.entity.Usuario;
import com.finnza.dto.request.ForgotPasswordRequest;
import com.finnza.dto.request.GoogleLoginRequest;
import com.finnza.dto.request.LoginRequest;
import com.finnza.dto.request.ResetPasswordRequest;
import com.finnza.dto.response.LoginResponse;
import com.finnza.dto.response.UsuarioDTO;
import com.finnza.repository.UsuarioRepository;
import com.finnza.security.JwtTokenProvider;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import java.security.SecureRandom;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.util.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Serviço de autenticação
 */
@Slf4j
@Service
@Transactional
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

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

    @Autowired
    private PasswordResetMailService passwordResetMailService;

    @Autowired
    private UsuarioService usuarioService;

    @Autowired
    private GoogleIdTokenVerifierService googleIdTokenVerifierService;

    @Value("${app.google.oauth.allow-auto-register:true}")
    private boolean googleAllowAutoRegister;

    /**
     * Login com Google (JWT {@code id_token} validado contra o Client ID do projeto).
     */
    public LoginResponse loginComGoogle(GoogleLoginRequest request) {
        if (!googleIdTokenVerifierService.isConfigured()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Login com Google não está configurado (defina GOOGLE_OAUTH_CLIENT_ID no servidor).");
        }
        GoogleIdToken.Payload payload;
        try {
            payload = googleIdTokenVerifierService.verifyAndGetPayload(request.getIdToken());
        } catch (Exception e) {
            log.warn("Falha técnica ao verificar id_token Google: {}", e.getMessage());
            throw new BadCredentialsException("Não foi possível validar o login com Google.");
        }
        if (payload == null) {
            throw new BadCredentialsException("Login Google inválido ou expirado. Tente novamente.");
        }
        if (!Boolean.TRUE.equals(payload.getEmailVerified())) {
            throw new BadCredentialsException("Confirme o e-mail na sua conta Google antes de entrar.");
        }
        Object emailObj = payload.getEmail();
        if (emailObj == null || emailObj.toString().isBlank()) {
            throw new BadCredentialsException("Conta Google sem e-mail público. Use outra conta.");
        }
        String email = emailObj.toString().trim().toLowerCase();
        String sub = payload.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new BadCredentialsException("Token Google incompleto.");
        }
        Object nameObj = payload.get("name");
        String nome = nameObj instanceof String ? (String) nameObj : null;

        Usuario usuario = usuarioService.sincronizarLoginGoogle(sub, email, nome, googleAllowAutoRegister);
        usuario = usuarioRepository.findByEmailWithPermissoes(usuario.getEmail()).orElse(usuario);

        UserDetails userDetails = userDetailsService.loadUserByUsername(usuario.getEmail());
        String token = tokenProvider.generateToken(userDetails);

        usuario.atualizarUltimoAcesso();
        usuarioRepository.save(usuario);

        return LoginResponse.builder()
                .token(token)
                .usuario(UsuarioDTO.fromEntity(usuario))
                .build();
    }

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

        } catch (BadCredentialsException e) {
            log.warn("Login recusado (credenciais) — email={}: {}", request.getEmail(), e.getMessage());
            throw e;
        } catch (org.springframework.security.core.userdetails.UsernameNotFoundException e) {
            log.warn("Login recusado — usuário não encontrado ou inativo: email={}", request.getEmail());
            throw new BadCredentialsException("Email ou senha inválidos");
        } catch (Exception e) {
            log.warn("Login recusado — email={} — {}: {}",
                    request.getEmail(), e.getClass().getSimpleName(), e.getMessage());
            log.debug("Stack login", e);
            throw new BadCredentialsException("Email ou senha inválidos");
        }
    }

    /**
     * Solicita recuperação de senha
     */
    public void solicitarRecuperacaoSenha(ForgotPasswordRequest request) {
        Optional<Usuario> opt = usuarioRepository.findByEmail(request.getEmail());
        if (opt.isEmpty() || !opt.get().isAtivo()) {
            log.info("Recuperação de senha: e-mail não cadastrado ou inativo (resposta genérica ao cliente).");
            return;
        }

        Usuario usuario = opt.get();
        String token = UUID.randomUUID().toString();
        String codigo = String.format("%06d", RANDOM.nextInt(1_000_000));
        usuario.setTokenResetSenha(token);
        usuario.setTokenResetSenhaExpiracao(LocalDateTime.now().plusHours(1));
        usuario.setResetSenhaCodigoHash(passwordEncoder.encode(codigo));
        usuarioRepository.save(usuario);

        passwordResetMailService.sendResetLink(usuario.getEmail(), usuario.getNome(), token, codigo);
    }

    /**
     * Redefine a senha usando o link do e-mail (token) ou e-mail + código de 6 dígitos.
     */
    public void redefinirSenha(ResetPasswordRequest request) {
        boolean useToken = StringUtils.hasText(request.getToken());
        boolean useCodigo = StringUtils.hasText(request.getEmail()) && StringUtils.hasText(request.getCodigo());

        if (useToken && useCodigo) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Use apenas o link do e-mail ou o código, não os dois ao mesmo tempo.");
        }
        if (!useToken && !useCodigo) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Informe o token do link ou o e-mail com o código de 6 dígitos.");
        }

        Usuario usuario;
        if (useToken) {
            usuario = usuarioRepository.findByTokenResetSenha(request.getToken().trim())
                    .orElseThrow(() -> new RuntimeException("Token inválido ou expirado"));
        } else {
            String email = request.getEmail().trim().toLowerCase();
            usuario = usuarioRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("Código inválido ou expirado"));
            if (!usuario.isAtivo() || usuario.getResetSenhaCodigoHash() == null) {
                throw new RuntimeException("Código inválido ou expirado");
            }
            if (!passwordEncoder.matches(request.getCodigo().trim(), usuario.getResetSenhaCodigoHash())) {
                throw new RuntimeException("Código inválido ou expirado");
            }
        }

        if (usuario.getTokenResetSenhaExpiracao() == null
                || usuario.getTokenResetSenhaExpiracao().isBefore(LocalDateTime.now())) {
            throw new RuntimeException(useToken ? "Token expirado" : "Código expirado");
        }

        usuario.setSenha(passwordEncoder.encode(request.getNovaSenha()));
        usuario.setTokenResetSenha(null);
        usuario.setTokenResetSenhaExpiracao(null);
        usuario.setResetSenhaCodigoHash(null);
        usuarioRepository.save(usuario);
    }
}

