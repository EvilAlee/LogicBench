package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.io.GateLibrary;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GalleryRenderTest {
    @Test
    void headlessGalleryPaintsAllTenDefinitionsAcrossFourFacings() {
        assertEquals(10, GateLibrary.load().all().size());
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
