#!/bin/bash
# Script para rodar o backend localmente com configurações do Asaas
# Não commite este script com a API Key!

export ASAAS_API_KEY="$aact_hmlg_000MzkwODA2MWY2OGM3MWRlMDU2NWM3MzJlNzZmNGZhZGY6OmI1ZTNjNTlkLTBlNzMtNGZjNC05MDhkLThhMTExNmMwYjhmNDo6JGFhY2hfYTk3YWI1MjMtMTBiNS00MjFiLTk4MjktMDg5NDM5YTVlYmFl"
export ASAAS_API_URL="https://sandbox.asaas.com/api/v3"
export ASAAS_MOCK_ENABLED="false"

mvn spring-boot:run
