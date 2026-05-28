"""One-off: limpa plano de contas (categoria_financeira_empresa) das empresas do usuário."""
import os
import sys
from datetime import datetime, timezone

import psycopg

EMAIL = "arthurbowens22@gmail.com"


def main() -> int:
    url = os.environ.get("FINNZIA_DB_URL", "").strip()
    if not url:
        print("Defina FINNZIA_DB_URL (postgresql://...)", file=sys.stderr)
        return 1

    with psycopg.connect(url, connect_timeout=30) as conn:
        conn.autocommit = False
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT id, nome, deleted
                FROM usuarios
                WHERE LOWER(TRIM(email)) = LOWER(TRIM(%s))
                ORDER BY id
                """,
                (EMAIL,),
            )
            users = cur.fetchall()
            if not users:
                print(f"Usuário não encontrado: {EMAIL}")
                conn.rollback()
                return 2

            print("Usuários encontrados:")
            for uid, nome, deleted in users:
                print(f"  id={uid} nome={nome!r} deleted={deleted}")

            user_ids = [u[0] for u in users]
            cur.execute(
                """
                SELECT eu.id_empresa, eu.nome_empresa, eu.ativo, eu.padrao
                FROM empresa_usuario eu
                WHERE eu.usuario_id = ANY(%s)
                ORDER BY eu.id_empresa
                """,
                (user_ids,),
            )
            empresas = cur.fetchall()
            if not empresas:
                print("Nenhuma empresa vinculada ao usuário.")
                conn.rollback()
                return 3

            empresa_ids = sorted({row[0] for row in empresas})
            print("Empresas do usuário:")
            for id_emp, nome_emp, ativo, padrao in empresas:
                print(f"  id_empresa={id_emp} nome={nome_emp!r} ativo={ativo} padrao={padrao}")

            cur.execute(
                """
                SELECT COUNT(*) FROM categoria_financeira_empresa
                WHERE id_empresa = ANY(%s) AND deleted = FALSE
                """,
                (empresa_ids,),
            )
            antes = cur.fetchone()[0]
            print(f"Categorias ativas antes: {antes}")

            if antes == 0:
                print("Plano de contas já está vazio (nenhuma categoria ativa).")
                conn.rollback()
                return 0

            now = datetime.now(timezone.utc)
            cur.execute(
                """
                UPDATE categoria_financeira_empresa
                SET deleted = TRUE,
                    data_exclusao = %s,
                    data_atualizacao = %s
                WHERE id_empresa = ANY(%s) AND deleted = FALSE
                """,
                (now, now, empresa_ids),
            )
            atualizadas = cur.rowcount
            conn.commit()
            print(f"Concluído: {atualizadas} linha(s) marcadas como excluídas (soft delete).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
