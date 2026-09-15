#!/data/data/com.termux/files/usr/bin/sh
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SDK_DIR=/data/data/com.termux/files/home/.cache/android-api/android-35
RESOURCE_SDK_DIR=/data/data/com.termux/files/home/.cache/android-api/android-9
TOOLS_DIR=/data/data/com.termux/files/usr/bin
OUT="$PROJECT_DIR/build"
RES_COMPILED="$OUT/res-compiled.zip"
RES_APK="$OUT/resources.apk"
GEN="$OUT/gen"
CLASSES="$OUT/classes"
DEX="$OUT/dex"
APK_NAME="Clima-Historico.apk"

rm -rf "$GEN" "$CLASSES" "$DEX" "$RES_COMPILED" "$RES_APK" "$OUT/classes.jar" "$OUT/unsigned.apk" "$OUT/Clima-Historico-aligned.apk" "$OUT/$APK_NAME"
mkdir -p "$GEN" "$CLASSES" "$DEX"

echo "[*] Compilando recursos..."
"$TOOLS_DIR/aapt2" compile --dir "$PROJECT_DIR/res" -o "$RES_COMPILED"
"$TOOLS_DIR/aapt2" link \
    -I "$RESOURCE_SDK_DIR/android.jar" \
    --manifest "$PROJECT_DIR/AndroidManifest.xml" \
    --java "$GEN" \
    --min-sdk-version 24 \
    --target-sdk-version 30 \
    --version-code 4 \
    --version-name 1.0.3 \
    --auto-add-overlay \
    -o "$RES_APK" -R "$RES_COMPILED"

echo "[*] Compilando Java..."
find "$PROJECT_DIR/src" "$GEN" -type f -name '*.java' -print > "$OUT/sources.list"
javac --release 8 -encoding UTF-8 \
    -classpath "$SDK_DIR/android.jar" \
    -d "$CLASSES" \
    @"$OUT/sources.list"

echo "[*] Stripping MethodParameters..."
TOOL_CLASSES="$OUT/tool-classes"
mkdir -p "$TOOL_CLASSES"
javac --release 8 -d "$TOOL_CLASSES" "$PROJECT_DIR/tools/StripMethodParameters.java"
java -cp "$TOOL_CLASSES" StripMethodParameters "$CLASSES"

echo "[*] Dex..."
jar cf "$OUT/classes.jar" -C "$CLASSES" .
"$TOOLS_DIR/d8" --release --min-api 24 --lib "$SDK_DIR/android.jar" \
    --output "$DEX" "$OUT/classes.jar"

echo "[*] Empacotando APK..."
cp "$RES_APK" "$OUT/unsigned.apk"
jar uf "$OUT/unsigned.apk" -C "$DEX" classes.dex
"$TOOLS_DIR/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/Clima-Historico-aligned.apk"

if [ ! -f "$OUT/clima-historico-release.keystore" ]; then
    keytool -genkeypair -noprompt \
        -keystore "$OUT/clima-historico-release.keystore" \
        -storepass climahistorico \
        -keypass climahistorico \
        -alias climahistorico \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Clima Historico, OU=Local, O=Clima Historico, L=Local, ST=SP, C=BR"
fi

"$TOOLS_DIR/apksigner" sign \
    --ks "$OUT/clima-historico-release.keystore" \
    --ks-pass pass:climahistorico \
    --key-pass pass:climahistorico \
    --out "$OUT/$APK_NAME" \
    "$OUT/Clima-Historico-aligned.apk"

"$TOOLS_DIR/apksigner" verify --verbose "$OUT/$APK_NAME"
DELIVERY_DIR=/storage/emulated/0/Documents
mkdir -p "$DELIVERY_DIR" 2>/dev/null || true
cp "$OUT/$APK_NAME" "$DELIVERY_DIR/$APK_NAME" 2>/dev/null || true
DOWNLOADS_DIR=/storage/emulated/0/Download
mkdir -p "$DOWNLOADS_DIR" 2>/dev/null || true
cp "$OUT/$APK_NAME" "$DOWNLOADS_DIR/$APK_NAME" 2>/dev/null || true
printf 'APK criado: %s\n' "$OUT/$APK_NAME"
printf 'APK em Documentos: %s\n' "$DELIVERY_DIR/$APK_NAME"
ls -lh "$OUT/$APK_NAME"
