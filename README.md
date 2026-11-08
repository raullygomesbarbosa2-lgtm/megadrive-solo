# Mega Drive Solo (Android nativo, protótipo)

App Android nativo para abrir ROMs próprias de Mega Drive sem WebView e sem depender de frontend instalado. Renderização, áudio e execução do sistema passam pela biblioteca C++ LibretroDroid usando o core Genesis Plus GX para Android ARM64 incluído em `app/src/main/jniLibs/arm64-v8a/`.

## Build / APK

GitHub Actions compila um APK debug em push para `main`; baixe o artefato `MegaDriveSolo-native-debug-apk` em Actions. Push também publica uma pré-release no GitHub Releases. Requer Android arm64-v8a; versão debug, não assinada para Play Store.

## Uso

Abra o app e escolha um dump próprio `.bin`, `.gen`, `.md`, `.smd` ou `.zip`. Não inclui jogos nem BIOS. Controles de tela e teste em aparelhos reais ainda não foram feitos; use controle Bluetooth/USB para jogar nesta versão inicial.

## Componentes e licenças

O frontend nativo deste app é código próprio simples. A execução do hardware emulado é fornecida por LibretroDroid e Genesis Plus GX, projetos de terceiros de código aberto. Isso é um app Android standalone, mas não é uma implementação nova de CPU/chips do Mega Drive feita do zero. Veja licenças de upstream antes de redistribuir.
