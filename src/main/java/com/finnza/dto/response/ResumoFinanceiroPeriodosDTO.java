package com.finnza.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Pacote com os resumos padrão usados no dashboard (mês corrente e ano corrente).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumoFinanceiroPeriodosDTO {

    private ResumoFinanceiroDTO mesAtual;
    private ResumoFinanceiroDTO anoAtual;
}
