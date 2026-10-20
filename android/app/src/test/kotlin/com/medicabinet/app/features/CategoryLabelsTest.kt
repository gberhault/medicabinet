package com.medicabinet.app.features

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.medicabinet.core.CategoryID
import com.medicabinet.core.reference.Taxonomy
import com.medicabinet.core.testing.SharedFixtures
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CategoryLabelsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun label(id: CategoryID, locale: Locale): String {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(configuration).getString(CategoryLabels.resource(id))
    }

    @Test
    fun everySharedCategoryHasALabelInEachLanguage() {
        for (case in SharedFixtures.categories()) {
            val id = CategoryID(case.name)
            val french = label(id, Locale.FRENCH)
            val english = label(id, Locale.ENGLISH)
            assertTrue(french.isNotBlank() && english.isNotBlank(), "${case.name} has an empty label")
            if (case.name !in SAME_IN_BOTH_LANGUAGES) assertNotEquals(french, english, "${case.name} is not translated")
        }
    }

    @Test
    fun labelsAreTheOnesOfTheRequirements() {
        assertEquals("Douleur et fièvre", label(CategoryID.PAIN_FEVER, Locale.FRENCH))
        assertEquals("Nose, throat and ears", label(CategoryID.COLD_ENT, Locale.ENGLISH))
        assertEquals("Trousse de secours", label(CategoryID.FIRST_AID, Locale.FRENCH))
        assertEquals("Uncategorized", label(CategoryID.UNCATEGORIZED, Locale.ENGLISH))
    }

    @Test
    fun anUnknownCategoryShowsTheOtherLabel() {
        val unknown = CategoryID("veterinary")
        for (locale in listOf(Locale.FRENCH, Locale.ENGLISH)) {
            assertEquals(label(CategoryID.OTHER, locale), label(unknown, locale))
        }
    }

    @Test
    fun labelsNeverNameAnIndication() {
        // A guard on the product rule: categories tidy the cabinet, they do not say what to take for what.
        val forbidden = listOf("traite", "soigne", "contre ", "treat", "cure", "relief", "against ")
        for (id in Taxonomy.ordered) {
            for (locale in listOf(Locale.FRENCH, Locale.ENGLISH)) {
                val text = label(id, locale).lowercase()
                assertTrue(forbidden.none { it in text }, "'$text' reads like an indication")
            }
        }
    }

    private companion object {
        /** Same word in French and English. */
        val SAME_IN_BOTH_LANGUAGES = setOf("digestion", "allergy")
    }
}
