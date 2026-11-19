package io.github.evilalee.gatelab.ui;

import java.awt.Image;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppIconsTest {
    @Test
    void loadsAtLeastOneClasspathIcon() {
        assertFalse(AppIcons.load().isEmpty());
    }

    @Test
    void missingResourceReturnsEmptyListWithoutThrowing() {
        List<Image> images = assertDoesNotThrow(() ->
                AppIcons.loadResources(AppIcons.class, "/icons/not-a-real-icon.png"));

        assertTrue(images.isEmpty());
    }
}
