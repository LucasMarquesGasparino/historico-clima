#!/data/data/com.termux/files/usr/bin/sh
# Concede permissões para Clima Histórico v1.0.3 - captura em background 01h/09h/15h + dedup
set -eu
PKG="com.historicoclima"
echo "[*] Verificando instalação..."
if ! pm list packages | grep -q "$PKG"; then
  echo "[!] $PKG não instalado. Toque em /storage/emulated/0/Documents/Clima-Historico.apk"
  exit 1
fi
echo "[✓] Instalado: $(pm path $PKG 2>&1 | head -1)"

echo "[*] 1) Alarme exato (SCHEDULE_EXACT_ALARM) - abre tela para ativar"
am start -a android.settings.REQUEST_SCHEDULE_EXACT_ALARM -d package:$PKG 2>&1 | head -5
echo "    -> Ative 'Clima Histórico' na lista que abriu"
sleep 1

echo "[*] 2) Ignorar otimização de bateria (crucial para app fechado)"
am start -a android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -d package:$PKG 2>&1 | head -5
echo "    -> Selecione 'Não otimizar'"
# Fallback genérico se o intent acima não existir no aparelho:
am start -a android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS 2>&1 | head -5 || true
sleep 1

echo "[*] 3) Notificações (POST_NOTIFICATIONS)"
am start -a android.settings.APP_NOTIFICATION_SETTINGS --es android.provider.extra.APP_PACKAGE $PKG 2>&1 | head -5
echo "    -> Ative 'Mostrar notificações' (necessário para foreground service)"
sleep 1

echo "[*] 4) Tentativas via pm/appops (podem falhar sem root, mas tentamos)"
pm grant $PKG android.permission.POST_NOTIFICATIONS 2>&1 | head -5 || echo "pm grant falhou (normal sem ADB)"
# Tentativa de whitelist via deviceidle (requer shell)
cmd deviceidle whitelist +$PKG 2>&1 | head -5 || dumpsys deviceidle whitelist +$PKG 2>&1 | head -5 || echo "whitelist via cmd falhou (faça manualmente em Bateria > Sem restrições)"

echo "[*] 5) Reagendando 3 alarmes + watchdog"
am broadcast -a com.historicoclima.TRIGGER_COLLECT 2>&1 | head -5 || true
# Alternativa direta:
# am broadcast -a com.historicoclima.ENABLE_FULL --ez enable false

echo ""
echo "[✓] Telas de permissão foram abertas. Confirme cada uma manualmente."
echo "Verifique em Clima Histórico > Coletas: deve mostrar '✓ Alarme exato permitido' e '✓ Ignorando otimização'"
