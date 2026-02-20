package com.finnza.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO para respostas de EmpresaUsuario
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmpresaUsuarioDTO {
    private Long id;
    private Integer idEmpresa;
    private String nomeEmpresa;
    private Boolean padrao;
    private Boolean ativo;
    private LocalDateTime dataCriacao;
    private LocalDateTime dataAtualizacao;
    private String removidoPor;
    private String motivoRemocao;
    private LocalDateTime dataRemocao;
}

/**
 * DTO simplificado para dropdowns e seletores
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class EmpresaUsuarioSimplificadoDTO {
    private Integer idEmpresa;
    private String nomeEmpresa;
    private Boolean padrao;
}

/**
 * DTO para request de atribuição de empresa ao usuário
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
class AtribuirEmpresaRequestDTO {
    private Integer idEmpresa;
    private String nomeEmpresa;
    private Boolean padrao = false;
}

/**
 * DTO para request de atualização em bulk (form de usuário)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
class AtualizarEmpresasRequestDTO {
    private Long[] empresaIds; // Array de IDs de empresa do BOMControle
    private Integer idEmpresaPadrao; // Qual deve ser padrão
    
    public AtualizarEmpresasRequestDTO(Integer[] empresaIds, Integer idEmpresaPadrao) {
        if (empresaIds != null) {
            this.empresaIds = new Long[empresaIds.length];
            for (int i = 0; i < empresaIds.length; i++) {
                this.empresaIds[i] = empresaIds[i].longValue();
            }
        }
        this.idEmpresaPadrao = idEmpresaPadrao;
    }
}

/**
 * DTO para remover acesso a empresa
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
class RemoverEmpresaRequestDTO {
    private Integer idEmpresa;
    private String motivo;
}

/**
 * DTO para resposta de operação
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class OperacaoResultadoDTO {
    private Boolean sucesso;
    private String mensagem;
    private Object dados;
}
