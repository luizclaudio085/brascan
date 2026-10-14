# BraScan

App Android (Kotlin + Jetpack Compose) para os ímãs Brastoy:

- **Campo**: magnetômetro em tempo real com o vetor B em **3D** (gira/zoom), rastro, eixos e detector de ímã ("Zerar ambiente").
- **Scanner**: foto de frente ou de cima → segmentação por cor encontra as peças sozinha (forma, tamanho, cor). Toque para corrigir.
- **3D**: modelo montado com as peças, girável; toque numa peça para trocar forma, tamanho, cor, orientação, profundidade e altura. Exporta JSON para o simulador MK-7.

Uma foto só mostra um lado, então a profundidade é estimada — ajuste no editor 3D.

## Atualização automática

Cada push na `main` dispara `.github/workflows/release.yml`, que compila, assina e publica `brascan.apk` + `version.json` em Releases (≈3–5 min).
Com o app aberto, ele consulta `releases/latest/download/version.json` a cada 30 s, baixa, confere o SHA-256 e instala.

- 1ª vez: instale o APK pelo link de Releases no celular e libere "instalar apps desconhecidos" para o BraScan quando ele pedir.
- A partir da 2ª atualização (Android 12+) a instalação é silenciosa: o app fecha e volta já atualizado.

### Assinatura

Todas as builds (local e CI) usam `brascan-release.jks`. Ele **não** vai para o git; no GitHub está nos secrets
`BRASCAN_KEYSTORE_B64` e `BRASCAN_STORE_PASSWORD`. Guarde uma cópia do `.jks` + `keystore.properties`: sem eles o celular recusa as atualizações.

## Compilar localmente

Precisa de JDK 17–21 (o JDK 25 do Android Studio não roda o Gradle 8.7):

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jdk-21.0.12.1+1"
.\gradlew.bat assembleDebug
```
