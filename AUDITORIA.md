# BraScan — Auditoria e estado das fases

Atualizado em 15/10/2026 · checkpoint `checkpoint-fase2` (commit 638a9a8) · release automática 1.4.

Legenda: **✅ validado** (teste automatizado ou emulador, com evidência) · **🟡 implementado sem validação física** (precisa de celular real) · **🟠 parcial** · **⬜ planejado** · **⛔ bloqueado**

Validação disponível neste ambiente: testes JVM (`app/src/test`), emulador Android 15 sem ARCore/Play Store,
fotos reais das peças enviadas pelo usuário. **Não há celular físico aqui**: nada de ARCore/câmera real foi medido.

## 1. Componentes existentes

| Área | Onde | Estado | Evidência / observação |
|---|---|---|---|
| Catálogo de peças | `vision/Catalog.kt` | ✅ | Mesmos tipos e polígonos do simulador (`vertices`/`tileShape` do index.html MK10.14). Médio = 0,81 do grande medido nas fotos (o simulador usa 0,65 — **divergência a resolver no simulador**). Lado grande = 7,5 cm (padrão do simulador; fotos sem régua). |
| Segmentação de imagem | `vision/Segmenter.kt` | ✅ | Lab + fundo aprendido + fechamento morfológico. `RealPhotoTest`: 18/18 peças visíveis nas 3 fotos reais. A ameia de plástico incolor não é detectável sobre fundo claro (limitação física, não bug). |
| Classificação de forma | `vision/Geometry2D.kt` | ✅ | Furos/entalhes/ângulos; `ShapeClassifierTest` com as 10 formas × 6 ângulos. |
| Orientação por molde | `TemplateFit` | ✅ | Encaixe do polígono exato (IoU), 8+ rotações; `recoversOrientation` ±8°. |
| Foto → peças | `vision/PhotoPieces.kt` | ✅ | Encaixe dentro de moldura, vidros da janela, tamanho relativo. |
| Colisão | `vision/Collision.kt` | ✅ | SAT entre partes convexas; encaixe-em-moldura permitido; chão. `CollisionTest` 7/7 (inclui pilha de 12 peças aleatórias → 0 sobreposição). |
| Malha das peças | `three/TileMesh.kt` | ✅ (emulador) | Aro, painel translúcido, ímãs nas posições do `magnetSites` do simulador, nervuras. |
| Renderizador | `three/GlRenderer.kt` | ✅ (emulador) | z-buffer, MSAA 4×, Blinn-Phong + Fresnel + hemisfério, sombra suave (7 amostras), cena preparada uma vez, translúcidos ordenados. FPS no celular real: **não medido**. |
| Scanner por foto → 3D | `scan/Pieces.kt`, `ui/ScanScreens.kt` | ✅ (emulador) | Foto real → 13 peças no 3D, sem interpenetração. |
| SLAM sem Google (padrão) | `vision/PlanarPose.kt`, `vision/PieceSlam.kt`, `ar/MarkerScanScreen.kt` | ✅ (sintético) / 🟡 (celular) | Peças como marcadores: cantos → homografia → refino; associação por consenso de hipóteses de câmera (relocaliza e fecha o laço); gravidade; escala por consistência entre vistas; forma votada no plano retificado. `PieceSlamTest` (volta completa, 480×360, só cor): posição 0,3–1,0 cm (quadrados/retângulo/moldura), 1,0–3,3 cm (quadrado de lado, com gravidade ruidosa), triângulo 0,5–1,5 cm e normal ~9°. Câmera do emulador estava travada: **tela ainda não vista funcionando com imagem**. |
| SLAM / ARCore (opcional) | `ar/ArScanner.kt` | 🟡 | Pose 6DoF, profundidade, nuvem de pontos e planos vêm do ARCore. Emulador sem ARCore: só o caminho de erro foi validado. |
| Fusão volumétrica | `vision/Fusion.kt` (voxels 6 mm) | ✅ (sintético) / 🟡 (real) | `SyntheticScanTest`: 6 peças, centro < 2 mm, normal < 1°, com ruído de 3 mm. Sem profundidade (só nuvem): acha as peças, forma imprecisa. |
| Exportação JSON | `Layout.json` | ✅ | Formato `projectData` do simulador (`items`, unidade = lado grande). Reimportação no simulador: **não testada ainda**. |
| Exportação B4/B5 | `scan/B5Export.kt`, `assets/b5codec.js` (extraído por `tools/extrair_codec_b5.py`) | ✅ | Codec **do próprio simulador** num WebView. Ida e volta: castelo real 1221 peças, exemplo 301 peças (`tools/testar_b5.cjs`, roda no CI). No celular: exporta → reimporta → compara. **Simulador real (MK-10.16) importou 13/13 peças** do código gerado no emulador a partir da foto real. Cores mapeadas para a paleta de 7 cores do formato. |
| Atualização GitHub | `.github/workflows/release.yml`, `update/Updater.kt` | ✅ | Push → testes → APK assinado → release + `version.json` (SHA-256). App atualizou sozinho 1.1 → 1.2 → 1.3 no emulador. Chave fora do repositório (secrets). |
| Magnetômetro | `ui/FieldScreen.kt` | ✅ (emulador) | Vetor 3D, detector de ímã. |

## 2. Bugs encontrados nesta auditoria

| # | Bug | Estado |
|---|---|---|
| 1 | Sombra no OpenGL com 8 floats por vértice (esperava 10) → crash `ArrayIndexOutOfBounds` na aba 3D | ✅ corrigido (1.3) |
| 2 | Retângulo/L/porta sem orientação (giro jogado fora no encaixe a 90°) | ✅ corrigido (molde + IoU) |
| 3 | Segmentação "vazava" entre cores vizinhas e se perdia no carpete | ✅ corrigido (Lab + média da região) |
| 4 | Triângulo retângulo virava "E" com reamostragem do Android | ✅ corrigido (triângulo antes dos entalhes, entalhe mínimo 5%) |
| 5 | Encaixe girado 45° dentro da moldura | ✅ corrigido (herda o giro da moldura) |
| 6 | JSON usava `pieces` e unidade própria (incompatível com o simulador) | ✅ corrigido (`items`, lado grande = 1) |
| 7 | Atualizador substitui builds de desenvolvimento de versão menor | ✔ comportamento esperado; builds de teste usam `VERSION_CODE` alto |
| 8 | AR: sobreposição recriada a cada quadro | ✅ corrigido (cache por lista de peças) |
| 9 | Peças em AR não passam pela colisão antes de exibir na câmera (só ao importar) | ⬜ |
| 11 | Exportação recusada pelo simulador (0/13): altura arredondada abaixo do chão, espessura escalada no médio, encaixe no mesmo plano da moldura | ✅ corrigido: espessura absoluta 0,065, folga > 1/256, encaixe empilhado → 13/13 |
| 12 | Celular do usuário não suporta ARCore (Play Store: "dispositivo não compatível") | ✅ scanner próprio sem Google é o padrão; opção ARCore só aparece se já estiver instalado |
| 10 | Tamanho do triângulo sai menor que o real (pontas perdem pixels) | 🟠 compensado ×1,1 (calibrado em 1 foto) |

## 3. Estado por fase do projeto mestre

| Fase | Estado |
|---|---|
| 1 Auditoria e bugs | ✅ este documento |
| 2 Geometria, colisões, renderização | ✅ (FPS real não medido) |
| 3 ARCore / SLAM 6DoF | 🟡 rastreamento, perda e motivo via ARCore; sem modo multivista sem ARCore |
| 4 Reconstrução multivista | 🟡 voxel hashing incremental + RANSAC/PCA; sem TSDF; sem tratamento explícito de oclusão |
| 5 Reconhecimento e associação | 🟠 foto ✅; AR associa por voxel/cor, sem rastrear identidade entre extrações |
| 6 Loop closure | ⬜ |
| 7 GPS / Wi-Fi RTT / BLE | ⬜ (auxiliar; não afeta posição de peça) |
| 8 Âncoras persistentes / retomada | ⬜ |
| 9 Inferência de interiores | ⬜ |
| 10 Mapa de confiança | 🟠 IoU do molde e suporte de voxels já calculados; falta exibir |
| 11 Active scanning | 🟠 cobertura em 12 setores + dicas simples |
| 12 Medições e Raio-X | ⬜ |
| 13 B5 validado | ✅ exportação conservadora; modo experimental depende das hipóteses (fase 9) |
| 14 Atualizações GitHub | ✅ |
| 15 Otimização e testes físicos | ⛔ depende de celular físico |

## 4. Divergências com o simulador (para decidir)

- **Tamanho médio**: simulador 0,65 × grande; foto real 0,81 (quadrado laranja 130 px × rosa 106 px na mesma foto).
- **Porta**: no simulador o vão é retangular e encostado embaixo (−0,48..0,30); nas peças reais o vão é em arco e centrado.
- **Janela 4 vidros e janela octogonal com encaixe**: no simulador `janela` e `moldura` têm a mesma geometria (furo quadrado 0,6).
- **Encaixe**: no simulador só existe no tamanho médio (lado 0,65); na foto mede ≈ 0,83 do grande.
