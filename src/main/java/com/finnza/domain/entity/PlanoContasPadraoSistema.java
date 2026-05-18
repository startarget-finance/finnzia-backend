package com.finnza.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "plano_contas_padrao_sistema")
@Getter
@Setter
public class PlanoContasPadraoSistema {

    @Id
    private Short id = 1;

    @Column(name = "conteudo_json", nullable = false, columnDefinition = "TEXT")
    private String conteudoJson;

    @Column(name = "data_atualizacao", nullable = false)
    private LocalDateTime dataAtualizacao = LocalDateTime.now();

    @Column(name = "atualizado_por_email")
    private String atualizadoPorEmail;
}
