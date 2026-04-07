package com.finnza.dto.dfc;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DfcPlanilhaResponseDTO {

    private Long id;
    private Integer idEmpresa;
    private List<String> months;
    private List<DfcPlanilhaLinhaDTO> rows;
}
