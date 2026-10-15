package com.brastoy.brascan.vision

import java.util.Locale

/**
 * Evidência de uma peça (de onde veio e quanto foi observada). Tudo aqui é medido, nada é "nota inventada".
 * [views]: quadros/fotos em que a peça apareceu. [spreadM]: espalhamento das posições observadas (m).
 * [fitIoU]: quanto o molde exato do catálogo cobriu a região vista (0..1). [shapeShare]: fração dos votos
 * de forma que foram para a forma escolhida. [accepted]: o usuário confirmou/editou a peça.
 */
data class Evidence(
    val source: Source,
    val views: Int = 1,
    val spreadM: Double = Double.NaN,
    val fitIoU: Float = Float.NaN,
    val shapeShare: Float = Float.NaN,
    val accepted: Boolean = false,
    val view: String = "", // foto: "frente" ou "cima"
) {
    enum class Source(val label: String) { FOTO("foto"), SCANNER("scanner 3D"), EXEMPLO("exemplo"), MANUAL("marcada à mão"), HIPOTESE("hipótese") }
}

/** Verde: observado e validado · Amarelo: inferido/pouco confiável · Vermelho: conflito · Cinza: desconhecido. */
enum class Level(val label: String, val argb: Int) {
    VERDE("observada e validada", 0xFF3DDC97.toInt()),
    AMARELO("inferida ou pouco confiável", 0xFFFFC531.toInt()),
    VERMELHO("conflito", 0xFFFF5C6C.toInt()),
    CINZA("desconhecida", 0xFF8DA2C0.toInt()),
}

data class Assessment(val level: Level, val reasons: List<String>)

object Confidence {
    private fun cm(m: Double) = String.format(Locale("pt", "BR"), "%.1f cm", m * 100)

    /**
     * [pushedM]: quanto a colisão precisou mover a peça para ela não atravessar outra (m).
     * [edgeM]: lado desta peça (m), para comparar o empurrão com o tamanho dela.
     */
    fun assess(e: Evidence?, pushedM: Double, edgeM: Double): Assessment {
        if (e == null || e.source == Evidence.Source.EXEMPLO) return Assessment(Level.CINZA, listOf("Peça de exemplo: nada foi observado"))
        if (e.source == Evidence.Source.HIPOTESE) return Assessment(if (e.accepted) Level.AMARELO else Level.CINZA,
            listOf("Hipótese (não foi vista): ${if (e.accepted) "aceita por você" else "aguardando você aceitar ou rejeitar"}"))
        val why = ArrayList<String>(); var level = Level.VERDE
        fun atLeast(l: Level) { if (l.ordinal > level.ordinal) level = l } // VERDE < AMARELO < VERMELHO

        when (e.source) {
            Evidence.Source.FOTO -> {
                atLeast(Level.AMARELO)
                why += if (e.view == "cima") "Foto de cima: largura e profundidade medidas; altura estimada" else "Foto de frente: largura e altura medidas; profundidade estimada (uma foto mostra um lado só)"
                if (!e.fitIoU.isNaN()) why += when {
                    e.fitIoU >= .8f -> "Contorno bate com o molde da peça (%.0f%%)".format(e.fitIoU * 100)
                    e.fitIoU >= .65f -> "Contorno bate só em parte com o molde (%.0f%%): forma pode estar errada".format(e.fitIoU * 100)
                    else -> "Contorno não bate com o molde (%.0f%%): forma provavelmente errada".format(e.fitIoU * 100)
                }
            }
            Evidence.Source.MANUAL -> { atLeast(Level.AMARELO); why += "Marcada à mão na foto: posição aproximada" }
            Evidence.Source.SCANNER -> {
                why += "Vista em ${e.views} quadros"
                if (e.views < 5) { atLeast(Level.AMARELO); why += "Poucas vistas: escaneie mais um pouco em volta" }
                if (!e.spreadM.isNaN()) when {
                    e.spreadM > .025 -> { atLeast(Level.VERMELHO); why += "Posições observadas discordam (±${cm(e.spreadM)})" }
                    e.spreadM > .01 -> { atLeast(Level.AMARELO); why += "Posição com incerteza de ±${cm(e.spreadM)}" }
                    else -> why += "Posição estável entre vistas (±${cm(e.spreadM)})"
                }
                if (!e.shapeShare.isNaN() && e.shapeShare < .7f) { atLeast(Level.AMARELO); why += "Forma em dúvida: só %.0f%% das vistas concordam".format(e.shapeShare * 100) }
            }
            Evidence.Source.EXEMPLO, Evidence.Source.HIPOTESE -> Unit
        }
        if (pushedM > edgeM * .3) { atLeast(Level.VERMELHO); why += "Conflito: atravessava outra peça e foi deslocada ${cm(pushedM)}" }
        else if (pushedM > edgeM * .08) { atLeast(Level.AMARELO); why += "Ajustada ${cm(pushedM)} para não atravessar outra peça" }

        if (e.accepted && level == Level.AMARELO) { level = Level.VERDE; why += "Confirmada por você" }
        else if (e.accepted) why += "Editada por você"
        return Assessment(level, why)
    }
}
