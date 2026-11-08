# Mega Drive Solo (Android nativo)

Player Android nativo, sem WebView, que usa LibretroDroid e o core Genesis Plus GX para executar jogos compatíveis. O vídeo é exibido na área de jogo em tela cheia imersiva (barras do sistema ocultas); a proporção original do jogo pode deixar faixas pretas em telas mais largas, preservando a imagem.

## APK

O GitHub Actions compila um APK debug para ARM e x86. Baixe a versão mais recente em [Releases](https://github.com/raullygomesbarbosa2-lgtm/megadrive-solo/releases) ou o artefato da execução em [Actions](https://github.com/raullygomesbarbosa2-lgtm/megadrive-solo/actions). APK debug é gratuito e não está assinado para publicação na Play Store; Android pode pedir confirmação para instalar apps de fontes externas.

## Uso e controles

Escolha um arquivo de jogo que você tenha direito de usar pelo seletor do Android. A tela do jogo ocupa todo o espaço disponível; no modo de jogo, use a alavanca virtual à esquerda para mover-se em qualquer direção e os botões A/B/C/X/Y/Z e START à direita. A alavanca e os botões podem ser usados juntos com multitouch. O menu ☰ permite trocar ROM e abrir opções.

Arquivos diretos comuns: `.bin`, `.gen`, `.md`, `.mdx`, `.smd` e `.rom`; jogos de outros sistemas suportados pelo core também podem ser reconhecidos. Arquivos `.zip`, `.7z` e `.rar` são aceitos quando contêm um desses formatos de jogo. Para arquivos compactados com várias ROMs, o app escolhe a primeira entrada compatível. Senhas de arquivo compactado não são suportadas. O reconhecimento de uma extensão não garante que cada imagem, dump, hack ou formato de disco funcione: depende do formato real e do suporte do Genesis Plus GX.

O app não inclui jogos nem BIOS. Use somente dumps obtidos legalmente.

## Componentes e licenças

Este é um aplicativo Android standalone, não uma implementação nova dos chips/CPU do console: execução, renderização e áudio vêm de LibretroDroid e Genesis Plus GX, projetos de terceiros. Consulte as licenças dos projetos upstream antes de redistribuir.
