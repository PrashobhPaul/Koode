package com.trippulse.app

import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.Pictures
import com.trippulse.app.domain.TransportCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PicturesTest {

    @Test fun everyTravelModeHasItsOwnPicture() {
        val pictures = TransportCatalog.ALL.map { Pictures.mode(it.key) }
        assertTrue("a mode without a picture: $pictures", pictures.none { it == null })
        assertEquals("two modes share a picture", pictures.size, pictures.toSet().size)
        assertEquals("auto", Pictures.mode("AUTO"))
        assertEquals("flight", Pictures.mode("FLIGHT"))
        assertEquals("cruise", Pictures.mode("SHIP"))
    }

    @Test fun pictureDrawnFacingLeftAreMirrored() {
        assertTrue(Pictures.modeFacesLeft("AUTO"))
        assertTrue(Pictures.modeFacesLeft("BUS"))
        assertTrue(!Pictures.modeFacesLeft("CAR"))
    }

    @Test fun eachStopShowsWhatItWas() {
        assertEquals(Pictures.FOOD, Pictures.event(EventTypes.FOOD_REPORTED))
        assertEquals(Pictures.WATER, Pictures.event(EventTypes.WATER_REPORTED))
        assertEquals(Pictures.REST, Pictures.event(EventTypes.REST_REPORTED))
        assertEquals(Pictures.TOILET, Pictures.event(EventTypes.TOILET_REPORTED))
        assertEquals(Pictures.FUEL, Pictures.event(EventTypes.FUEL_STOP))
        assertEquals(Pictures.FUEL, Pictures.event(EventTypes.CHARGE_STOP))
        assertEquals(Pictures.STAY, Pictures.event(EventTypes.OVERNIGHT_CONFIRMED))
        assertEquals(Pictures.STAY, Pictures.event(EventTypes.HALT_CONFIRMED))
        assertEquals(Pictures.WALK, Pictures.event(EventTypes.DEBOARDED))
        // Tea and a snack are not a plate of food; they keep their cup and biscuit.
        assertNull(Pictures.event(EventTypes.TEA_COFFEE_REPORTED))
        assertNull(Pictures.event(EventTypes.SNACK_REPORTED))
        assertEquals(Pictures.FOOD, Pictures.event(EventTypes.FOOD_ACKNOWLEDGED))
        assertNull(Pictures.event(EventTypes.TOLL_CROSSED))
    }

    @Test fun aBreakShowsItsMainReason() {
        fun brk(vararg items: String) = Pictures.event(EventTypes.BREAK_CHECKPOINT, items.associateWith { true })
        assertEquals(Pictures.RESTAURANT, brk("water", "food", "toilet"))
        assertEquals(Pictures.RESTAURANT, brk("tea"))
        assertEquals(Pictures.FUEL, brk("fuel", "toilet"))
        assertEquals(Pictures.REST, brk("rest", "water"))
        assertEquals(Pictures.TOILET, brk("toilet", "water"))
        assertEquals(Pictures.WATER, brk("water"))
        assertEquals(Pictures.REST, brk())
    }

    @Test fun savedPlacesByWhatTheyAre() {
        assertEquals(Pictures.HOME, Pictures.place("Home"))
        assertEquals(Pictures.HOME, Pictures.place("Amma's house"))
        assertEquals(Pictures.STAY, Pictures.place("Munnar resort"))
        assertEquals(Pictures.STAY, Pictures.place("Hotel Taj"))
        assertEquals(Pictures.RESTAURANT, Pictures.place("Kumarakom dhaba"))
        assertEquals(Pictures.FUEL, Pictures.place("Petrol bunk, Vytilla"))
        assertEquals(Pictures.BUILDING, Pictures.place("Office"))
        assertEquals(Pictures.BUILDING, Pictures.place("Skyline apartments"))
    }

    /** Every picture name exists as an app drawable and as a web viewer file. */
    @Test fun everyPictureShipsInTheAppAndTheWebViewer() {
        val module = listOf(File("."), File("apps/android/app")).first { File(it, "src/main/res").isDirectory }
        val web = File(module, "../../../web/art")
        val missing = Pictures.ALL.flatMap { name ->
            listOfNotNull(
                "drawable-nodpi/art_$name.webp".takeUnless { File(module, "src/main/res/$it").isFile },
                "web/art/$name.webp".takeUnless { File(web, "$name.webp").isFile }
            )
        }
        assertTrue("missing pictures: $missing", missing.isEmpty())
    }
}
