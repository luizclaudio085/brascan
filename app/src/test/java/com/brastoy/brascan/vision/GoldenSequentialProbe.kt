package com.brastoy.brascan.vision

import org.junit.Test

/**
 * Sonda: os goldens (DetectorGoldenTest e ShapeGoldenTest) com os laços paralelos desligados (Par.enabled = false, tudo
 * numa thread só). Tem que bater igual ao paralelo: o resultado de cada índice não depende da ordem nem das threads.
 */
class GoldenSequentialProbe {
    @Test fun sequentialMatchesGolden() {
        Par.enabled = false
        try { DetectorGoldenTest.check(); ShapeGoldenTest().golden() } finally { Par.enabled = true }
    }
}
