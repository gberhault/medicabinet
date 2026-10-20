package com.medicabinet.app.features.cabinet

import com.medicabinet.app.app.AppEnvironment
import com.medicabinet.core.Box
import com.medicabinet.core.CategoryID
import com.medicabinet.core.NamedProductRepository
import com.medicabinet.core.cabinet.BoxEdit
import com.medicabinet.core.reference.ReferenceProduct
import java.time.Duration
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** What saving a box edit remembers as the product the household named (#57). */
@OptIn(ExperimentalCoroutinesApi::class)
class BoxDetailViewModelMemoryTest {
    private val unknown = "03401900000003"
    private val kit = CabinetTestKit()
    private val named: NamedProductRepository get() = kit.store.named

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    private suspend fun loaded(box: Box, environment: AppEnvironment = kit.environment): BoxDetailViewModel {
        val model = BoxDetailViewModel(box.id, environment, UnconfinedTestDispatcher())
        // The state is a WhileSubscribed flow: subscribe so that `save` sees the loaded box.
        model.state.first { it is BoxDetailState.Loaded }
        return model
    }

    @Test
    fun aNewNameIsRememberedForAnUnknownCode() = runTest {
        kit.open()
        val box = kit.box("Test", unknown, CabinetTestKit.FINE)
        val result = loaded(box).save(BoxEdit.from(box).copy(name = "Crème solaire"))
        assertEquals(SaveResult.Saved, result)
        assertEquals("Crème solaire", named.namedProduct(unknown)?.name)
    }

    @Test
    fun aChosenCategoryIsRememberedWithItsFlag() = runTest {
        kit.open()
        val box = kit.box("Crème solaire", unknown, CabinetTestKit.FINE)
        loaded(box).save(BoxEdit.from(box).chooseCategory(CategoryID.SKIN_WOUND))
        val remembered = assertNotNull(named.namedProduct(unknown))
        assertEquals(CategoryID.SKIN_WOUND, remembered.category)
        assertEquals(true, remembered.categoryOverridden)
    }

    @Test
    fun anExpiryOnlyChangeRemembersNothing() = runTest {
        kit.open()
        val box = kit.box("Crème solaire", unknown, CabinetTestKit.FINE)
        val result = loaded(box).save(BoxEdit.from(box).copy(expiry = CabinetTestKit.SOON))
        assertEquals(SaveResult.Saved, result)
        assertNull(named.namedProduct(unknown))
    }

    @Test
    fun anExpiryOnlyChangeNeverBringsAForgottenProductBack() = runTest {
        kit.open()
        val box = kit.box("Crème solaire", unknown, CabinetTestKit.FINE)
        named.remember(unknown, "Crème solaire", CategoryID.UNCATEGORIZED, categoryOverridden = false)
        named.forget(assertNotNull(named.namedProduct(unknown)).id)
        kit.clock.advance(Duration.ofSeconds(60))
        loaded(box).save(BoxEdit.from(box).copy(expiry = CabinetTestKit.SOON))
        assertNull(named.namedProduct(unknown))
    }

    @Test
    fun aCodeTheReferenceKnowsIsNeverRemembered() = runTest {
        val known = CabinetTestKit(mapOf("3400935955838" to ReferenceProduct("PRODUIT")))
        known.open()
        val box = known.box("Produit", "03400935955838", CabinetTestKit.FINE)
        loaded(box, known.environment).save(BoxEdit.from(box).copy(name = "Mon produit"))
        assertNull(known.store.named.namedProduct("03400935955838"))
    }

    @Test
    fun aFailingMemoryLeavesTheSaveSuccessful() = runTest {
        kit.open()
        val box = kit.box("Test", unknown, CabinetTestKit.FINE)
        val failing = object : NamedProductRepository by named {
            override suspend fun remember(
                gtin: String,
                name: String,
                category: CategoryID,
                categoryOverridden: Boolean
            ): Unit = throw IllegalStateException("disk full")
        }
        val environment = AppEnvironment(
            kit.store,
            kit.store,
            failing,
            kit.clock,
            kit.environment.reference,
            ZoneOffset.UTC
        )
        val result = loaded(box, environment).save(BoxEdit.from(box).copy(name = "Crème solaire"))
        assertIs<SaveResult.Saved>(result)
        assertEquals("Crème solaire", kit.store.box(box.id)?.product?.name)
        assertNull(named.namedProduct(unknown))
    }
}
