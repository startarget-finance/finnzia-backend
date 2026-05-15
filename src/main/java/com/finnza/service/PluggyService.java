package com.finnza.service;

import com.finnza.config.PluggyProperties;
import com.finnza.domain.entity.PluggyConexao;
import com.finnza.domain.entity.Usuario;
import com.finnza.dto.request.PluggyRegisterItemRequest;
import com.finnza.dto.response.PluggyConexaoResponse;
import com.finnza.dto.response.PluggyConnectTokenResponse;
import com.finnza.dto.response.PluggyStatusResponse;
import com.finnza.integration.pluggy.PluggyApiClient;
import com.finnza.repository.PluggyConexaoRepository;
import com.finnza.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PluggyService {

    private final PluggyProperties pluggyProperties;
    private final PluggyApiClient pluggyApiClient;
    private final UsuarioRepository usuarioRepository;
    private final PluggyConexaoRepository pluggyConexaoRepository;

    public PluggyStatusResponse status() {
        return PluggyStatusResponse.builder()
                .configured(isConfigured())
                .sandboxMode(pluggyProperties.isSandboxMode())
                .includeSandbox(pluggyProperties.isIncludeSandbox())
                .build();
    }

    public boolean isConfigured() {
        return pluggyProperties.isEnabled()
                && pluggyProperties.getClientId() != null
                && !pluggyProperties.getClientId().isBlank()
                && pluggyProperties.getClientSecret() != null
                && !pluggyProperties.getClientSecret().isBlank();
    }

    @Transactional(readOnly = true)
    public List<PluggyConexaoResponse> listarConexoes(String emailUsuario) {
        Usuario usuario = usuarioRepository
                .findByEmail(emailUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
        return pluggyConexaoRepository.findByUsuario_IdOrderByDataCriacaoDesc(usuario.getId()).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public PluggyConnectTokenResponse criarConnectToken(String emailUsuario, String itemIdParaAtualizar) {
        if (!isConfigured()) {
            throw new IllegalStateException("Integração Pluggy não configurada (PLUGGY_ENABLED e credenciais).");
        }
        Usuario usuario = usuarioRepository
                .findByEmail(emailUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
        String clientUserId = "finnza-user-" + usuario.getId();
        String apiKey = pluggyApiClient.getOrCreateApiKey();
        String accessToken = pluggyApiClient.createConnectToken(apiKey, clientUserId, itemIdParaAtualizar);
        return PluggyConnectTokenResponse.builder().accessToken(accessToken).build();
    }

    @Transactional
    public PluggyConexaoResponse registrarOuAtualizarItem(String emailUsuario, PluggyRegisterItemRequest request) {
        Usuario usuario = usuarioRepository
                .findByEmail(emailUsuario)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
        String itemId = request.getItemId().trim();
        PluggyConexao conexao = pluggyConexaoRepository
                .findByPluggyItemId(itemId)
                .orElse(PluggyConexao.builder().usuario(usuario).pluggyItemId(itemId).build());
        if (conexao.getId() != null && !conexao.getUsuario().getId().equals(usuario.getId())) {
            throw new IllegalArgumentException("Item já vinculado a outro usuário");
        }
        conexao.setUsuario(usuario);
        conexao.setConnectorId(trimToNull(request.getConnectorId()));
        conexao.setConnectorName(trimToNull(request.getConnectorName()));
        conexao.setStatus(trimToNull(request.getStatus()));
        conexao = pluggyConexaoRepository.save(conexao);
        return toResponse(conexao);
    }

    @Transactional
    public void atualizarConexaoPorWebhook(String pluggyItemId, String status, String eventoJson) {
        if (pluggyItemId == null || pluggyItemId.isBlank()) {
            return;
        }
        pluggyConexaoRepository
                .findByPluggyItemId(pluggyItemId.trim())
                .ifPresent(c -> {
                    if (status != null && !status.isBlank()) {
                        c.setStatus(status.trim());
                    }
                    if (eventoJson != null) {
                        String ev = eventoJson.length() > 8000 ? eventoJson.substring(0, 8000) + "…" : eventoJson;
                        c.setUltimoEvento(ev);
                    }
                    pluggyConexaoRepository.save(c);
                });
    }

    private PluggyConexaoResponse toResponse(PluggyConexao c) {
        return PluggyConexaoResponse.builder()
                .id(c.getId())
                .pluggyItemId(c.getPluggyItemId())
                .connectorId(c.getConnectorId())
                .connectorName(c.getConnectorName())
                .status(c.getStatus())
                .dataCriacao(c.getDataCriacao())
                .dataAtualizacao(c.getDataAtualizacao())
                .build();
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
