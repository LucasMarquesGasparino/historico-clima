#!/data/data/com.termux/files/usr/bin/sh
# Ativa modo completo (5571 cidades) e dispara coleta imediata
set -eu
PKG="com.historicoclima"
if ! pm list packages | grep -q "$PKG"; then
  echo "[!] App não instalado. Instale /storage/emulated/0/Documents/Clima-Historico.apk primeiro."
  exit 1
fi
echo "[*] Ativando modo completo (todos os 5571 municípios)..."
am broadcast -a com.historicoclima.ENABLE_FULL --ez enable true 2>&1
echo "[✓] Broadcast enviado. Verifique em Coletas -> Modo de coleta deve mostrar '● Modo completo ATIVO'"
sleep 1
echo "[*] Disparando coleta imediata (foreground service)..."
am broadcast -a com.historicoclima.TRIGGER_COLLECT 2>&1
# alternativa direta via service (se broadcast falhar)
am broadcast -a com.historicoclima.COLLECT 2>&1 || true
echo "[✓] Coleta disparada — veja notificação 'Coletando clima...' e em Coletas -> Última coleta"
echo ""
echo "Para voltar ao modo econômico (27 capitais + favoritas):"
echo "  am broadcast -a com.historicoclima.ENABLE_FULL --ez enable false"
echo ""
echo "Para adicionar cidade específica (ex: São Paulo IBGE 3550308):"
echo "  am broadcast -a com.historicoclima.ADD_CITY --ei code 3550308"
