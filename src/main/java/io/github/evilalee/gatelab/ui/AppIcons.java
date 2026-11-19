package io.github.evilalee.gatelab.ui;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** Loads the optional classpath icons used by LogicBench windows. */
public final class AppIcons {
    private static final Logger LOGGER = Logger.getLogger(AppIcons.class.getName());
    private static final List<Image> ICONS = loadResources(
            AppIcons.class,
            "/icons/logicbench-16.png",
            "/icons/logicbench-32.png",
            "/icons/logicbench-48.png",
            "/icons/logicbench-64.png",
            "/icons/logicbench-128.png",
            "/icons/logicbench-256.png");

    private AppIcons() {
    }

    /**
     * Returns the successfully decoded icons. The classpath resources are loaded once,
     * before Swing's event-dispatch thread is started by {@link Main}.
     */
    public static List<Image> load() {
        return ICONS;
    }

    static List<Image> loadResources(Class<?> resourceAnchor, String... resourcePaths) {
        List<Image> images = new ArrayList<>();
        if (resourceAnchor == null || resourcePaths == null) {
            return List.of();
        }

        for (String resourcePath : resourcePaths) {
            if (resourcePath == null || resourcePath.isBlank()) {
                warn(resourcePath);
                continue;
            }
            try (InputStream stream = resourceAnchor.getResourceAsStream(resourcePath)) {
                if (stream == null) {
                    warn(resourcePath);
                    continue;
                }
                Image image = ImageIO.read(stream);
                if (image == null) {
                    warn(resourcePath);
                    continue;
                }
                images.add(image);
            } catch (IOException | RuntimeException exception) {
                warn(resourcePath);
            }
        }
        return List.copyOf(images);
    }

    private static void warn(String resourcePath) {
        try {
            LOGGER.warning("Skipping missing or unreadable application icon: " + resourcePath);
        } catch (RuntimeException ignored) {
            // Icon loading must remain best-effort even when logging is unavailable.
        }
    }
}
