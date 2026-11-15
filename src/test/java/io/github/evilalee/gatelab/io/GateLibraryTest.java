package io.github.evilalee.gatelab.io;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.SVGLoader;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GateLibraryTest {
    private static final int GRID = 10;

    @Test
    void gatesJsonLoadsTenDefinitionsAndEverySvgParsesWithJsvg() {
        GateLibrary library = GateLibrary.load();

        assertEquals(10, library.all().size());
        assertSame(library, GateLibrary.load());

        SVGLoader loader = new SVGLoader();
        for (GateDef definition : library.all()) {
            URL resource = library.svgUrl(definition);
            assertNotNull(resource, definition.id() + " SVG URL");
            SVGDocument document = loader.load(resource);
            assertNotNull(document, definition.id() + " SVG document");
            assertTrue(document.viewBox().width > 0, definition.id() + " SVG viewBox width");
            assertTrue(document.viewBox().height > 0, definition.id() + " SVG viewBox height");
        }
    }

    @Test
    void everyDimensionOriginAndPinCoordinateIsOnTheTenUnitGridAndPinsFit() {
        for (GateDef definition : GateLibrary.load().all()) {
            assertGrid(definition.width());
            assertGrid(definition.height());
            assertGrid(definition.origin().x());
            assertGrid(definition.origin().y());

            for (GateGeometry.Offset pin : definition.inputs()) {
                assertGrid(pin.x());
                assertGrid(pin.y());
                assertInside(definition, pin);
            }
            if (definition.output() != null) {
                assertGrid(definition.output().x());
                assertGrid(definition.output().y());
                assertInside(definition, definition.output());
            }
        }
    }

    private static void assertInside(GateDef definition, GateGeometry.Offset pin) {
        assertTrue(pin.x() >= 0 && pin.x() <= definition.width(),
                definition.id() + " pin x " + pin.x());
        assertTrue(pin.y() >= 0 && pin.y() <= definition.height(),
                definition.id() + " pin y " + pin.y());
    }

    private static void assertGrid(int value) {
        assertEquals(0, value % GRID, "coordinate/dimension must be grid aligned: " + value);
    }
}
