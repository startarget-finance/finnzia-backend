package com.finnza.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;

/**
 * Valida o {@code id_token} JWT emitido pelo Google (Sign in with Google).
 */
@Slf4j
@Service
public class GoogleIdTokenVerifierService {

    private final String clientId;
    private volatile GoogleIdTokenVerifier verifier;

    public GoogleIdTokenVerifierService(@Value("${app.google.oauth.client-id:}") String clientId) {
        this.clientId = clientId != null ? clientId.trim() : "";
    }

    public boolean isConfigured() {
        return StringUtils.hasText(clientId);
    }

    /**
     * @return payload do token ou {@code null} se inválido / audience incorreta
     */
    public GoogleIdToken.Payload verifyAndGetPayload(String idTokenString) throws Exception {
        if (!StringUtils.hasText(idTokenString)) {
            return null;
        }
        if (!isConfigured()) {
            log.warn("Login Google: app.google.oauth.client-id não configurado.");
            return null;
        }
        GoogleIdTokenVerifier v = verifier();
        GoogleIdToken idToken = v.verify(idTokenString);
        if (idToken == null) {
            return null;
        }
        return idToken.getPayload();
    }

    private GoogleIdTokenVerifier verifier() {
        if (verifier == null) {
            synchronized (this) {
                if (verifier == null) {
                    verifier = new GoogleIdTokenVerifier.Builder(
                            new NetHttpTransport(),
                            GsonFactory.getDefaultInstance())
                            .setAudience(Collections.singletonList(clientId))
                            .build();
                }
            }
        }
        return verifier;
    }
}
