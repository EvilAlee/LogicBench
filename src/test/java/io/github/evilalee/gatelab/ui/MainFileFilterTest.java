package io.github.evilalee.gatelab.ui;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainFileFilterTest {
    @Test
    void chooserAcceptsTheCompoundLogicBenchExtension() {
        var filter = Main.schematicFileFilter();

        assertTrue(filter.accept(new File("SRlatch.logicbench.json")));
        assertTrue(filter.accept(new File("SRlatch.LOGICBENCH.JSON")));
    }

    @Test
    void chooserRejectsJsonFilesWithOtherExtensions() {
        var filter = Main.schematicFileFilter();

        assertFalse(filter.accept(new File("SRlatch.json")));
        assertFalse(filter.accept(new File("SRlatch.logicbench.json.bak")));
        assertFalse(filter.accept(new File("SRlatch.lbc")));
    }
}
