package com.finnza.service;

import com.finnza.integration.resend.ResendMailClient;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * E-mail transacional: recuperação de senha, convite de novo usuário e código no perfil.
 *
 * <p><strong>Produção em PaaS com SMTP bloqueado (ex.: Render free):</strong> use {@code APP_MAIL_PROVIDER=resend}
 * e variáveis {@code RESEND_API_KEY}, {@code RESEND_FROM} — tráfego só HTTPS (443).
 *
 * <p><strong>Local / legado:</strong> {@code APP_MAIL_PROVIDER=smtp} (padrão) com {@code MAIL_USERNAME} /
 * {@code MAIL_PASSWORD}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetMailService {

    /** Nome da marca em textos enviados ao usuário (assunto, rodapé HTML, etc.). */
    private static final String BRAND = "Finzzia";

    private final JavaMailSender javaMailSender;
    private final ObjectProvider<ResendMailClient> resendMailClient;

    @Value("${app.mail.password-reset.enabled:false}")
    private boolean mailEnabled;

    @Value("${app.mail.provider:smtp}")
    private String mailProvider;

    @Value("${app.mail.resend.api-key:}")
    private String resendApiKey;

    @Value("${app.mail.resend.from:}")
    private String resendFrom;

    @Value("${spring.mail.username:}")
    private String smtpUsername;

    @Value("${spring.mail.password:}")
    private String smtpPassword;

    @Value("${app.mail.password-reset.from-name:Finzzia}")
    private String fromDisplayName;

    @Value("${app.frontend.password-reset-base-url:https://www.finzzia.com.br}")
    private String frontendBaseUrl;

    /**
     * Envia o e-mail com o link de reset. Não propaga exceção: o token já foi persistido;
     * falhas de envio são registradas para diagnóstico.
     */
    public void sendResetLink(String recipientEmail, String recipientName, String token, String codigoSeisDigitos) {
        if (!mailEnabled) {
            log.info(
                    "Recuperação de senha: envio de e-mail desligado (APP_MAIL_PASSWORD_RESET_ENABLED=false). Token gravado para {}.",
                    maskEmail(recipientEmail));
            return;
        }
        if (!mailConfigured()) {
            log.warn(
                    "Recuperação de senha: transporte de e-mail incompleto (SMTP ou Resend); e-mail não enviado para {}.",
                    maskEmail(recipientEmail));
            return;
        }

        String base = frontendBaseUrl.trim().replaceAll("/+$", "");
        String link = base + "/esqueci-senha?token=" + token;

        String greeting = StringUtils.hasText(recipientName) ? escapeHtml(recipientName) : "Olá";
        String codigoBlockHtml = "";
        String codigoBlockPlain = "";
        if (StringUtils.hasText(codigoSeisDigitos)) {
            String c = escapeHtml(codigoSeisDigitos.trim());
            codigoBlockHtml = "<p><strong>Código de verificação:</strong> <span style=\"font-size:22px;letter-spacing:4px;font-family:monospace\">"
                    + c + "</span> (válido por 1 hora; você pode colar o código na página de recuperação.)</p>";
            codigoBlockPlain = "Código de verificação (válido por 1 hora): " + codigoSeisDigitos.trim() + "\n\n";
        }
        String html = """
                <p>%s,</p>
                <p>Recebemos um pedido para redefinir a senha da sua conta %s.</p>
                %s
                <p><a href="%s">Ou clique aqui para abrir a página de nova senha</a> (mesmo prazo de 1 hora).</p>
                <p>Se você não solicitou, ignore este e-mail.</p>
                <p style="font-size:12px;color:#666;">%s</p>
                """
                .formatted(greeting, BRAND, codigoBlockHtml, escapeHtmlAttribute(link), BRAND);

        String plain = (StringUtils.hasText(recipientName) ? recipientName : "Olá")
                + ",\n\n"
                + codigoBlockPlain
                + "Link para redefinir a senha (válido por 1 hora):\n"
                + link
                + "\n\nSe você não solicitou, ignore este e-mail.\n";

        deliver(
                recipientEmail,
                "Redefinição de senha — " + BRAND,
                plain,
                html,
                "recuperação de senha",
                true);
    }

    /**
     * Convite: conta criada por um administrador — envia acesso e senha provisória.
     * Falha de e-mail não reverte o cadastro (evita bloqueio quando SMTP está indisponível).
     */
    public void sendNovaContaConvite(String recipientEmail, String recipientName, String senhaProvisoria) {
        if (!mailEnabled || !mailConfigured()) {
            log.warn(
                    "Convite de nova conta: e-mail não enviado para {}. "
                            + "Ative APP_MAIL_PASSWORD_RESET_ENABLED=true e configure Resend (APP_MAIL_PROVIDER=resend, RESEND_*) "
                            + "ou SMTP (MAIL_USERNAME, MAIL_PASSWORD). mailEnabled={}, mailConfigured={}.",
                    maskEmail(recipientEmail),
                    mailEnabled,
                    mailConfigured());
            return;
        }
        String base = frontendBaseUrl.trim().replaceAll("/+$", "");
        String loginUrl = base + "/login";
        String greeting = StringUtils.hasText(recipientName) ? escapeHtml(recipientName) : "Olá";
        String safeEmail = escapeHtml(recipientEmail.trim());
        String html = """
                <p>%s,</p>
                <p>Sua conta <strong>%s</strong> foi criada.</p>
                <p><strong>E-mail de acesso:</strong> %s</p>
                <p><strong>Senha provisória:</strong> <code style="font-size:15px">%s</code></p>
                <p><a href="%s">Acessar o sistema</a></p>
                <p style="font-size:13px;color:#555;">Por segurança, após entrar altere a senha em <strong>Meu perfil</strong>.</p>
                <p style="font-size:12px;color:#666;">%s</p>
                """
                .formatted(
                        greeting,
                        BRAND,
                        safeEmail,
                        escapeHtml(senhaProvisoria),
                        escapeHtmlAttribute(loginUrl),
                        BRAND);
        String plain = (StringUtils.hasText(recipientName) ? recipientName.trim() : "Olá")
                + ",\n\n"
                + "Sua conta " + BRAND + " foi criada.\n"
                + "E-mail de acesso: " + recipientEmail.trim() + "\n"
                + "Senha provisória: " + senhaProvisoria + "\n\n"
                + "Acesse: " + loginUrl
                + "\n\nAltere a senha após o primeiro acesso em Meu perfil.\n";
        deliver(
                recipientEmail,
                "Sua conta " + BRAND + " foi criada",
                plain,
                html,
                "convite novo usuário",
                true);
    }

    /**
     * Código para confirmar alteração de senha no perfil (válido poucos minutos).
     */
    public void sendAlteracaoSenhaCodigo(String recipientEmail, String recipientName, String codigoSeisDigitos) {
        if (!mailEnabled || !mailConfigured()) {
            log.warn(
                    "Alteração de senha: transporte incompleto; código não enviado para {}.",
                    maskEmail(recipientEmail));
            return;
        }
        String greeting = StringUtils.hasText(recipientName) ? escapeHtml(recipientName) : "Olá";
        String c = escapeHtml(codigoSeisDigitos.trim());
        String html = """
                <p>%s,</p>
                <p>Use o código abaixo para <strong>confirmar a alteração da sua senha</strong> no %s (válido por 15 minutos).</p>
                <p style="font-size:24px;letter-spacing:6px;font-family:monospace;font-weight:bold">%s</p>
                <p>Se você não solicitou, ignore este e-mail e sua senha permanece a mesma.</p>
                <p style="font-size:12px;color:#666;">%s</p>
                """
                .formatted(greeting, BRAND, c, BRAND);
        String plain = (StringUtils.hasText(recipientName) ? recipientName.trim() : "Olá")
                + ",\n\n"
                + "Código para alterar sua senha (15 minutos): " + codigoSeisDigitos.trim()
                + "\n\nInforme-o em Meu perfil junto com a senha atual e a nova senha.\n";
        deliver(
                recipientEmail,
                "Código para alterar senha — " + BRAND,
                plain,
                html,
                "código alteração senha",
                true);
    }

    private boolean useResend() {
        return mailProvider != null && "resend".equalsIgnoreCase(mailProvider.strip());
    }

    private boolean smtpReady() {
        return StringUtils.hasText(smtpUsername) && StringUtils.hasText(smtpPassword);
    }

    private boolean mailConfigured() {
        if (useResend()) {
            return resendMailClient.getIfAvailable() != null
                    && StringUtils.hasText(resendApiKey)
                    && StringUtils.hasText(resendFrom);
        }
        return smtpReady();
    }

    /**
     * @param swallowExceptions se true, só regista o erro (fluxos em que o e-mail é melhor esforço).
     */
    private void deliver(
            String to,
            String subject,
            String plain,
            String html,
            String logContext,
            boolean swallowExceptions) {
        try {
            if (useResend()) {
                ResendMailClient client = resendMailClient.getIfAvailable();
                if (client == null) {
                    throw new IllegalStateException("Cliente Resend não disponível (verifique APP_MAIL_PROVIDER=resend).");
                }
                client.send(resendFrom.trim(), to, subject, html, plain);
            } else {
                MimeMessage message = javaMailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
                helper.setFrom(new InternetAddress(smtpUsername, fromDisplayName, "UTF-8"));
                helper.setTo(to);
                helper.setSubject(subject);
                helper.setText(plain, html);
                javaMailSender.send(message);
            }
            log.info("E-mail ({}) enviado para {}", logContext, maskEmail(to));
        } catch (Exception e) {
            log.error("Falha ao enviar e-mail ({}) para {}", logContext, maskEmail(to), e);
            if (!swallowExceptions) {
                if (e instanceof RuntimeException re) {
                    throw re;
                }
                throw new IllegalStateException("Falha ao enviar e-mail (" + logContext + ")", e);
            }
        }
    }

    private static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "(vazio)";
        }
        int at = email.indexOf('@');
        if (at < 0) {
            return "***";
        }
        if (at <= 1) {
            return "***" + email.substring(at);
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String escapeHtmlAttribute(String s) {
        return escapeHtml(s).replace("'", "&#39;");
    }
}
