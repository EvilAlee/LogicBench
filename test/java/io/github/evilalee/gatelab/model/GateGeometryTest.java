package io.github.evilalee.gatelab.model;

import io.github.evilalee.gatelab.io.GateLibrary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GateGeometryTest {
    private static final GateGeometry.Offset ZERO = new GateGeometry.Offset(0, 0);

    @Test
    void outputOriginRemainsAtZeroForEveryFacing() {
        for (GateDef definition : GateLibrary.load().all()) {
            if (definition.output() != null) {
                for (Facing facing : Facing.values()) {
                    assertEquals(ZERO, GateGeometry.outputOffset(definition, facing),
                            definition.id() + " output at " + facing);
                }
            } else {
                assertEquals(ZERO, GateGeometry.pinOffset(
                        definition, definition.inputs().getFirst(), Facing.EAST));
            }
        }
    }

    @Test
    void fourClockwiseQuarterTurnsReturnEveryOffsetToItsStart() {
        List<GateGeometry.Offset> offsets = List.of(
                new GateGeometry.Offset(10, 20),
                new GateGeometry.Offset(-30, 40),
                new GateGeometry.Offset(0, -50));
        for (GateGeometry.Offset original : offsets) {
            GateGeometry.Offset rotated = original;
            for (int step = 0; step < 4; step++) {
                rotated = GateGeometry.rotate(rotated, Facing.SOUTH);
            }
            assertEquals(original, rotated);
        }
    }

    @Test
    void allRotatedPinsStayOnTheTenUnitGridAndOutputFacesCardinalDirection() {
        for (GateDef definition : GateLibrary.load().all()) {
            for (Facing facing : Facing.values()) {
                for (GateGeometry.Offset offset : GateGeometry.inputOffsets(definition, facing)) {
                    assertGrid(offset.x());
                    assertGrid(offset.y());
                }
                GateGeometry.Offset output = GateGeometry.outputOffset(definition, facing);
                if (output != null) {
                    assertGrid(output.x());
                    assertGrid(output.y());
                }
            }
        }

        assertEquals(new GateGeometry.Offset(1, 0),
                GateGeometry.rotate(new GateGeometry.Offset(1, 0), Facing.EAST));
        assertEquals(new GateGeometry.Offset(0, 1),
                GateGeometry.rotate(new GateGeometry.Offset(1, 0), Facing.SOUTH));
        assertEquals(new GateGeometry.Offset(-1, 0),
                GateGeometry.rotate(new GateGeometry.Offset(1, 0), Facing.WEST));
        assertEquals(new GateGeometry.Offset(0, -1),
                GateGeometry.rotate(new GateGeometry.Offset(1, 0), Facing.NORTH));
    }

    @Test
    void northAndSouthBoundsSwapWidthAndHeight() {
        for (GateDef definition : GateLibrary.load().all()) {
            GateGeometry.Bounds east = GateGeometry.bounds(definition, Facing.EAST);
            GateGeometry.Bounds north = GateGeometry.bounds(definition, Facing.NORTH);
            GateGeometry.Bounds south = GateGeometry.bounds(definition, Facing.SOUTH);

            assertEquals(east.height(), north.width(), definition.id() + " north width");
            assertEquals(east.width(), north.height(), definition.id() + " north height");
            assertEquals(east.height(), south.width(), definition.id() + " south width");
            assertEquals(east.width(), south.height(), definition.id() + " south height");
        }
    }

    private static void assertGrid(int value) {
        assertEquals(0, value % 10, "rotated offset must remain on grid: " + value);
    }
}
