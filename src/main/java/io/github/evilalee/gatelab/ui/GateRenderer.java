package io.github.evilalee.gatelab.ui;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.view.ViewBox;
import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Retained SVG documents and Java2D rendering for built-in components. */
public final class GateRenderer {
    private final Map<String, SVGDocument> documentsById;

    public GateRenderer(GateLibrary library) {
        Objects.requireNonNull(library, "library");
        Map<String, SVGDocument> documentsByResource = new HashMap<>();
        Map<String, SVGDocument> byId = new HashMap<>();
        SVGLoader loader = new SVGLoader();
        for (GateDef definition : library.all()) {
            SVGDocument document = documentsByResource.computeIfAbsent(
                    definition.svgResource(), resource -> {
                        SVGDocument loaded = loader.load(library.svgUrl(definition));
                        if (loaded == null) {
                            throw new IllegalStateException(
                                    "JSVG could not load " + resource + " for " + definition.id());
                        }
                        return loaded;
                    });
            byId.put(definition.id(), document);
        }
        documentsById = Map.copyOf(byId);
    }

    /**
     * Paints one component in the current world transform. The origin stays
     * fixed while the artwork and its pin geometry rotate around it.
     */
    public void render(
            Graphics2D graphics,
            GateDef definition,
            Facing facing,
            double worldX,
            double worldY) {
        Objects.requireNonNull(graphics, "graphics");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(facing, "facing");

        SVGDocument document = documentsById.get(definition.id());
        if (document == null) {
            throw new IllegalArgumentException("no SVG loaded for " + definition.id());
        }

        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                    RenderingHints.VALUE_STROKE_PURE);
            g2.translate(worldX, worldY);
            g2.rotate(facing.rotationRadians());
            GateGeometry.Offset origin = definition.origin();
            ViewBox viewBox = document.viewBox();
            g2.scale(definition.width() / viewBox.width,
                    definition.height() / viewBox.height);
            g2.translate(-origin.x(), -origin.y());
            document.render(null, g2, viewBox);
        } finally {
            g2.dispose();
        }
    }
}
