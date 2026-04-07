package com.finnza.repository.specification;

import com.finnza.domain.entity.ContaBancariaParam;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ContaBancariaParamSpecification {

    private ContaBancariaParamSpecification() {}

    public static Specification<ContaBancariaParam> naoDeletado() {
        return (root, query, cb) -> cb.isFalse(root.get("deleted"));
    }

    public static Specification<ContaBancariaParam> textoBusca(String q) {
        if (!StringUtils.hasText(q)) {
            return (root, query, cb) -> cb.conjunction();
        }
        String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.like(cb.lower(root.get("banco")), like));
            ps.add(cb.like(cb.lower(root.get("nomeConta")), like));
            ps.add(cb.like(cb.lower(root.get("instituicao")), like));
            ps.add(cb.like(cb.lower(root.get("agencia")), like));
            ps.add(cb.like(cb.lower(root.get("conta")), like));
            return cb.or(ps.toArray(Predicate[]::new));
        };
    }

    public static Specification<ContaBancariaParam> possuiEmpresa(Integer idEmpresa) {
        if (idEmpresa == null || idEmpresa <= 0) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> {
            query.distinct(true);
            Join<ContaBancariaParam, Integer> j = root.join("idEmpresas", JoinType.INNER);
            return cb.equal(j, idEmpresa);
        };
    }

    public static Specification<ContaBancariaParam> ativo(Boolean ativo) {
        if (ativo == null) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.equal(root.get("ativo"), ativo);
    }

    public static Specification<ContaBancariaParam> visivelParaEmpresasUsuario(Set<Integer> empresasUsuario) {
        if (empresasUsuario == null || empresasUsuario.isEmpty()) {
            return (root, query, cb) -> cb.disjunction();
        }
        return (root, query, cb) -> {
            query.distinct(true);
            Join<ContaBancariaParam, Integer> j = root.join("idEmpresas", JoinType.INNER);
            Predicate alguma = j.in(empresasUsuario);
            return cb.or(cb.isEmpty(root.get("idEmpresas")), alguma);
        };
    }
}
