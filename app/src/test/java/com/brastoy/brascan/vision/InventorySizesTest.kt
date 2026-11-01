package com.brastoy.brascan.vision

import com.brastoy.brascan.scan.Inventory
import org.junit.Assert.assertEquals
import org.junit.Test

/** Os tamanhos possíveis de cada forma no detector ([Catalog.sizesOf]) são os do inventário do simulador (código B8). */
class InventorySizesTest {
    @Test fun sameAsInventoryKinds() {
        for (shape in Catalog.SHAPES) {
            val inv = Inventory.KINDS.filter { it.type == shape }.map { it.size }.toSet()
            assertEquals(shape, inv, Catalog.sizesOf(shape).toSet())
        }
        assertEquals(Inventory.KINDS.size, Catalog.SHAPES.sumOf { Catalog.sizesOf(it).size })
    }
}
