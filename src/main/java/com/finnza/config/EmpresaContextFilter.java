package com.finnza.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Lê o header X-Empresa-Id e define o contexto da empresa para a requisição.
 * Assim o AsaasService (e outros) podem usar a API key do Asaas da empresa correta.
 */
@Component
public class EmpresaContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String header = request.getHeader("X-Empresa-Id");
            if (header != null && !header.isBlank()) {
                try {
                    int id = Integer.parseInt(header.trim());
                    EmpresaContextHolder.setIdEmpresa(id);
                } catch (NumberFormatException ignored) {
                    EmpresaContextHolder.clear();
                }
            } else {
                EmpresaContextHolder.clear();
            }
            filterChain.doFilter(request, response);
        } finally {
            EmpresaContextHolder.clear();
        }
    }
}
