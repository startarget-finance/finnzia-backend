package com.finnza.repository.specification;

import com.finnza.domain.entity.Cliente;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Filtros da listagem paginada de clientes (parametrização).
 */
public final class ClienteCadastroSpecification {

    private ClienteCadastroSpecification() {}

    public static Specification<Cliente> naoDeletado() {
        return (root, query, cb) -> cb.isFalse(root.get("deleted"));
    }

    public static Specification<Cliente> textoBusca(String q) {
        if (!StringUtils.hasText(q)) {
            return (root, query, cb) -> cb.conjunction();
        }
        String t = q.trim();
        String like = "%" + t.toLowerCase(Locale.ROOT) + "%";
        String digits = t.replaceAll("\\D", "");
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.like(cb.lower(root.get("razaoSocial")), like));
            ps.add(cb.like(cb.lower(root.get("nomeFantasia")), like));
            ps.add(cb.like(cb.lower(root.get("cpfCnpj")), like));
            if (!digits.isEmpty()) {
                ps.add(cb.like(root.get("cpfCnpj"), "%" + digits + "%"));
            }
            return cb.or(ps.toArray(Predicate[]::new));
        };
    }

    public static Specification<Cliente> classificacaoIgual(Integer c) {
        if (c == null || c < 1 || c > 5) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.equal(root.get("classificacao"), c);
    }

    public static Specification<Cliente> tipoPessoaIgual(Cliente.TipoPessoa tp) {
        if (tp == null) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.equal(root.get("tipoPessoa"), tp);
    }

    /** Filtra clientes que possuem a empresa na coleção (interseção). */
    public static Specification<Cliente> possuiEmpresa(Integer idEmpresa) {
        if (idEmpresa == null || idEmpresa <= 0) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> {
            query.distinct(true);
            Join<Cliente, Integer> j = root.join("idEmpresas", JoinType.INNER);
            return cb.equal(j, idEmpresa);
        };
    }

    /**
     * Sem empresas vinculadas (visível a todos) ou com pelo menos uma empresa na lista do usuário.
     */
    public static Specification<Cliente> visivelParaEmpresasUsuario(Set<Integer> empresasUsuario) {
        if (empresasUsuario == null || empresasUsuario.isEmpty()) {
            return (root, query, cb) -> cb.disjunction();
        }
        return (root, query, cb) -> {
            query.distinct(true);
            Join<Cliente, Integer> j = root.join("idEmpresas", JoinType.INNER);
            Predicate algumaDaLista = j.in(empresasUsuario);
            return cb.or(cb.isEmpty(root.get("idEmpresas")), algumaDaLista);
        };
    }
}
