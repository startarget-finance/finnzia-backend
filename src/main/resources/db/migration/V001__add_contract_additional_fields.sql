-- Flyway Migration: V001__add_contract_additional_fields.sql
-- Descrição: Adiciona campos adicionais à entidade Contrato
-- Data: 2026-02-13
-- Autor: Finnzia Team

-- Adiciona campos de informações comerciais e administrativas ao contrato
ALTER TABLE contratos ADD COLUMN IF NOT EXISTS data_venda DATE;
ALTER TABLE contratos ADD COLUMN IF NOT EXISTS data_encerramento DATE;
ALTER TABLE contratos ADD COLUMN IF NOT EXISTS link_contrato VARCHAR(500);
ALTER TABLE contratos ADD COLUMN IF NOT EXISTS status_assinatura VARCHAR(20);
ALTER TABLE contratos ADD COLUMN IF NOT EXISTS projeto VARCHAR(100);
ALTER TABLE contratos ADD COLUMN IF NOT EXISTS valor_entrada DECIMAL(15, 2);

-- Cria índices para melhor performance em consultas
CREATE INDEX IF NOT EXISTS idx_contrato_data_venda ON contratos(data_venda);
CREATE INDEX IF NOT EXISTS idx_contrato_status_assinatura ON contratos(status_assinatura);
CREATE INDEX IF NOT EXISTS idx_contrato_projeto ON contratos(projeto);

-- Comentários nas colunas para documentação
COMMENT ON COLUMN contratos.data_venda IS 'Data em que a venda/contrato foi realizado';
COMMENT ON COLUMN contratos.data_encerramento IS 'Data prevista para o encerramento do contrato';
COMMENT ON COLUMN contratos.link_contrato IS 'URL/link para o documento do contrato';
COMMENT ON COLUMN contratos.status_assinatura IS 'Status da assinatura digital: PENDENTE, ASSINADO, CANCELADO';
COMMENT ON COLUMN contratos.projeto IS 'Identificação ou nome do projeto associado ao contrato';
COMMENT ON COLUMN contratos.valor_entrada IS 'Valor de entrada/adiantamento do contrato';
