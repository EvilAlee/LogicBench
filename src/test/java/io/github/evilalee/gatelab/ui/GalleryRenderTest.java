package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GalleryRenderTest {
    @Test
    void rowPitchFitsTheLargestRotatedBoundsWithGridAlignedMargin() {
        List<GateDef> definitions = GateLibrary.load().all();
        int largestDimension = definitions.stream()
                .flatMap(definition -> java.util.Arrays.stream(Facing.values())
                        .map(facing -> GateGeometry.bounds(definition, facing)))
                .mapToInt(bounds -> Math.max(bounds.width(), bounds.height()))
                .max()
                .orElseThrow();

        int rowPitch = CanvasPanel.galleryRowPitch(definitions);

        int expectedPitch = ((largestDimension + 20 + 9) / 10) * 10;
        assertEquals(expectedPitch, rowPitch);
    }

    @Test
    void firstGalleryRowStartsBelowTheDedicatedHeaderSpace() {
        List<GateDef> definitions = GateLibrary.load().all();
        int firstOriginY = CanvasPanel.galleryFirstOriginY(definitions);

        for (GateDef definition : definitions) {
            for (Facing facing : Facing.values()) {
                GateGeometry.Bounds bounds = GateGeometry.bounds(definition, facing);
                assertTrue(firstOriginY + bounds.y() >= 40,
                        definition.id() + " " + facing + " overlaps gallery headers");
            }
        }
    }

    @Test
    void headlessGalleryPaintsAllElevenVisibleDefinitionsAcrossFourFacings() {
        assertEquals(11, CanvasPanel.galleryDefinitions(GateLibrary.load().all()).size());
        assertEquals(4, Facing.values().length);

        CanvasPanel canvas = new CanvasPanel(true);
        canvas.setSize(1000, 800);
        BufferedImage image = new BufferedImage(1000, 800, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            assertDoesNotThrow(() -> canvas.paint(graphics));
        } finally {
            graphics.dispose();
        }
        assertTrue(canvas.arePinMarkersVisible());
        assertTrue(canvas.isGalleryMode());
    }
}
