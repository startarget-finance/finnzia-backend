package com.finnza.dto.dfc;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DfcPlanilhaLinhaDTO {

    private String id;
    private String label;
    /** title | item | subitem | result */
    private String type;
    /** + ou - */
    private String sign;
    /** chave = mês (ex: Jan/25), valor numérico ou null */
    private Map<String, Object> values;
    /** apenas type=result: ids de linhas title somadas */
    private List<String> titleIds;
}
