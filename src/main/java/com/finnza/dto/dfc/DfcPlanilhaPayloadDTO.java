package com.finnza.dto.dfc;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DfcPlanilhaPayloadDTO {

    @NotNull
    private List<String> months;

    @NotNull
    private List<DfcPlanilhaLinhaDTO> rows;
}
