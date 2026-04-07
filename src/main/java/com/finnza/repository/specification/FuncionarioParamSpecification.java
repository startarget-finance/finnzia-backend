package com.finnza.repository.specification;

import com.finnza.domain.entity.FuncionarioParam;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class FuncionarioParamSpecification {

    private FuncionarioParamSpecification() {}

    public static Specification<FuncionarioParam> naoDeletado() {
        return (root, query, cb) -> cb.isFalse(root.get("deleted"));
    }

    public static Specification<FuncionarioParam> textoBusca(String q) {
        if (!StringUtils.hasText(q)) {
            return (root, query, cb) -> cb.conjunction();
        }
        String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        String digits = q.trim().replaceAll("\\D", "");
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.like(cb.lower(root.get("nomeCompleto")), like));
            ps.add(cb.like(cb.lower(root.get("cargo")), like));
            ps.add(cb.like(cb.lower(root.get("departamento")), like));
            ps.add(cb.like(cb.lower(root.get("email")), like));
            ps.add(cb.like(cb.lower(root.get("cpf")), like));
            if (!digits.isEmpty()) {
                ps.add(cb.like(root.get("cpf"), "%" + digits + "%"));
            }
            return cb.or(ps.toArray(Predicate[]::new));
        };
    }

    public static Specification<FuncionarioParam> possuiEmpresa(Integer idEmpresa) {
        if (idEmpresa == null || idEmpresa <= 0) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> {
            query.distinct(true);
            Join<FuncionarioParam, Integer> j = root.join("idEmpresas", JoinType.INNER);
            return cb.equal(j, idEmpresa);
        };
    }

    public static Specification<FuncionarioParam> ativo(Boolean ativo) {
        if (ativo == null) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.equal(root.get("ativo"), ativo);
    }

    public static Specification<FuncionarioParam> visivelParaEmpresasUsuario(Set<Integer> empresasUsuario) {
        if (empresasUsuario == null || empresasUsuario.isEmpty()) {
            return (root, query, cb) -> cb.disjunction();
        }
        return (root, query, cb) -> {
            query.distinct(true);
            Join<FuncionarioParam, Integer> j = root.join("idEmpresas", JoinType.INNER);
            Predicate algumaDaLista = j.in(empresasUsuario);
            return cb.or(cb.isEmpty(root.get("idEmpresas")), algumaDaLista);
        };
    }
}
