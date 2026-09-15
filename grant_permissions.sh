#!/data/data/com.termux/files/usr/bin/sh
# Script para conceder permissões do Clima Histórico
# Requer que o APK já esteja instalado (toque em /storage/emulated/0/Documents/Clima-Historico.apk)
set -eu
PKG="com.historicoclima"
echo "[*] Verificando instalação..."
if ! pm list packages | grep -q "$PKG"; then
  echo "[!] Pacote $PKG NÃO instalado. Instale primeiro:"
  echo "    toque em /storage/emulated/0/Documents/Clima-Historico.apk"
  echo "    ou: termux-open /storage/emulated/0/Documents/Clima-Historico.apk"
  exit 1
fi
echo "[✓] Pacote encontrado: $(pm path $PKG 2>&1 || echo sem path)"

echo "[*] 1) Abrindo permissão de Alarme Exato (SCHEDULE_EXACT_ALARM)..."
# Android 12+ exige consentimento explícito. Este intent abre a tela onde o usuário ativa.
am start -a android.settings.REQUEST_SCHEDULE_EXACT_ALARM -d package:$PKG 2>&1 || \
am start -a android.settings.REQUEST_SCHEDULE_EXACT_ALARM --es android.provider.extra.APP_PACKAGE $PKG 2>&1 || true
echo "    -> Ative a chave para 'Clima Histórico' e volte."

sleep 1
echo "[*] 2) Abrindo otimização de bateria (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)..."
am start -a android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -d package:$PKG 2>&1 || true
echo "    -> Escolha 'Não otimizar' / 'Permitir' e volte."

sleep 1
echo "[*] 3) Abrindo configurações de notificações..."
am start -a android.settings.APP_NOTIFICATION_SETTINGS --es android.provider.extra.APP_PACKAGE $PKG 2>&1 || \
am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$PKG 2>&1 || true
echo "    -> Ative 'Mostrar notificações'."

sleep 1
echo "[*] 4) Tentando conceder POST_NOTIFICATIONS via pm (pode falhar sem ADB/root, mas tentamos)..."
pm grant $PKG android.permission.POST_NOTIFICATIONS 2>&1 || echo "    (pm grant falhou — conceda manualmente na tela anterior)"

echo "[*] 5) Checando alarmes agendados..."
am broadcast -a com.historicoclima.TRIGGER_COLLECT 2>&1 | head -20 || true

echo ""
echo "[✓] Passos de permissão disparados. Verifique manualmente nas telas abertas."
echo "    Se o app ainda mostrar '⚠ Alarme exato NEGADO', volte em Coletas e ative."
