package com.medicabinet.app.features

import com.medicabinet.core.CategoryID
import com.medicabinet.core.reference.Taxonomy
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import org.junit.Test

class CategoryIconsTest {
    @Test
    fun everyTaxonomyCategoryHasItsOwnIcon() {
        val icons = Taxonomy.ordered.map { CategoryIcons.icon(it) }
        assertEquals(Taxonomy.ordered.size, icons.toSet().size, "two categories share an icon")
    }

    @Test
    fun anUnknownCategoryGetsTheOtherIcon() {
        assertEquals(CategoryIcons.icon(CategoryID.OTHER), CategoryIcons.icon(CategoryID("veterinary")))
        assertNotSame(CategoryIcons.icon(CategoryID.UNCATEGORIZED), CategoryIcons.icon(CategoryID.OTHER))
    }
}
