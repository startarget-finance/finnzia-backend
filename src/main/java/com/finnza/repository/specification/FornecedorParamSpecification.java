package com.finnza.repository.specification;

import com.finnza.domain.entity.Cliente;
import com.finnza.domain.entity.FornecedorParam;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class FornecedorParamSpecification {

    private FornecedorParamSpecification() {}

    public static Specification<FornecedorParam> naoDeletado() {
        return (root, query, cb) -> cb.isFalse(root.get("deleted"));
    }

    public static Specification<FornecedorParam> textoBusca(String q) {
        if (!StringUtils.hasText(q)) {
            return (root, query, cb) -> cb.conjunction();
        }
        String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        String digits = q.trim().replaceAll("\\D", "");
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.like(cb.lower(root.get("razaoSocial")), like));
            ps.add(cb.like(cb.lower(root.get("nomeFantasia")), like));
            ps.add(cb.like(cb.lower(root.get("email")), like));
            ps.add(cb.like(cb.lower(root.get("cpfCnpj")), like));
            if (!digits.isEmpty()) {
                ps.add(cb.like(root.get("cpfCnpj"), "%" + digits + "%"));
            }
            return cb.or(ps.toArray(Predicate[]::new));
        };
    }

    public static Specification<FornecedorParam> tipoPessoaIgual(Cliente.TipoPessoa tp) {
        if (tp == null) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.equal(root.get("tipoPessoa"), tp);
    }

    public static Specification<FornecedorParam> possuiEmpresa(Integer idEmpresa) {
        if (idEmpresa == null || idEmpresa <= 0) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> {
            query.distinct(true);
            Join<FornecedorParam, Integer> j = root.join("idEmpresas", JoinType.INNER);
            return cb.equal(j, idEmpresa);
        };
    }

    public static Specification<FornecedorParam> ativo(Boolean ativo) {
        if (ativo == null) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.equal(root.get("ativo"), ativo);
    }

    public static Specification<FornecedorParam> visivelParaEmpresasUsuario(Set<Integer> empresasUsuario) {
        if (empresasUsuario == null || empresasUsuario.isEmpty()) {
            return (root, query, cb) -> cb.disjunction();
        }
        return (root, query, cb) -> {
            query.distinct(true);
            Join<FornecedorParam, Integer> j = root.join("idEmpresas", JoinType.INNER);
            Predicate alguma = j.in(empresasUsuario);
            return cb.or(cb.isEmpty(root.get("idEmpresas")), alguma);
        };
    }
}
