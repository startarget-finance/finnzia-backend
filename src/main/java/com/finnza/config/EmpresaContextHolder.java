package com.finnza.config;

/**
 * Armazena o ID da empresa (Bom Controle) do contexto da requisição atual.
 * Preenchido por filtro que lê o header X-Empresa-Id.
 * Usado pelo AsaasService para escolher a API key do Asaas por empresa.
 */
public final class EmpresaContextHolder {

    private static final ThreadLocal<Integer> CURRENT_ID_EMPRESA = new ThreadLocal<>();

    public static void setIdEmpresa(Integer idEmpresa) {
        if (idEmpresa != null && idEmpresa > 0) {
            CURRENT_ID_EMPRESA.set(idEmpresa);
        } else {
            CURRENT_ID_EMPRESA.remove();
        }
    }

    public static Integer getIdEmpresa() {
        return CURRENT_ID_EMPRESA.get();
    }

    public static void clear() {
        CURRENT_ID_EMPRESA.remove();
    }
}
