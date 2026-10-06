# Grava docs/demo.gif com o VHS (https://github.com/charmbracelet/vhs) a partir de scripts/demo.sh.
# Com os serviços rodando no host:
#   docker run --rm --add-host host.docker.internal:host-gateway -v "$PWD:/vhs" <imagem-vhs-com-curl-e-jq> scripts/demo.tape
Output docs/demo.gif

Set Shell bash
Set FontSize 15
Set Width 1280
Set Height 860
Set Padding 20
Set Theme "Catppuccin Mocha"
Set TypingSpeed 45ms

Hide
Type "export PSP_URL=http://host.docker.internal:8091 MERCHANT_URL=http://host.docker.internal:8090 PS1='$ '" Enter
Type "clear" Enter
Show

Type "./scripts/demo.sh" Sleep 600ms Enter
Wait+Screen@60s /inbox/
Sleep 6s
