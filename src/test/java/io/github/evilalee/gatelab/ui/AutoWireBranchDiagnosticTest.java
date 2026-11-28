package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.WireSegment;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for pin and existing-net auto-route attachments. */
class AutoWireBranchDiagnosticTest {
    @Test
    void oneOutputCanFanOutToThreeInputsInThreeRoutesAndUndoRestoresExactGeometry() {
        CanvasPanel canvas = new CanvasPanel();
        canvas.setSize(900, 700);
        Schematic schematic = canvas.schematic();
        var source = schematic.add("input", 100, 100, Facing.EAST);
        var sink1 = schematic.add("output", 300, 100, Facing.EAST);
        var sink2 = schematic.add("output", 200, 260, Facing.EAST);
        var sink3 = schematic.add("output", 430, 260, Facing.EAST);
        var unrelatedSource = schematic.add("input", 650, 450, Facing.EAST);
        var unrelatedSink = schematic.add("output", 850, 450, Facing.EAST);

        Connectivity.PinPosition output = pin(schematic, source.id(), "out");
        Connectivity.PinPosition input1 = pin(schematic, sink1.id(), "in0");
        Connectivity.PinPosition input2 = pin(schematic, sink2.id(), "in0");
        Connectivity.PinPosition input3 = pin(schematic, sink3.id(), "in0");
        Connectivity.PinPosition unrelatedOut = pin(schematic, unrelatedSource.id(), "out");
        Connectivity.PinPosition unrelatedIn = pin(schematic, unrelatedSink.id(), "in0");

        schematic.addWire(new WireSegment(output.x(), output.y(), input1.x(), input1.y()));
        schematic.addWire(new WireSegment(
                unrelatedOut.x(), unrelatedOut.y(), unrelatedIn.x(), unrelatedIn.y()));

        AtomicReference<String> status = new AtomicReference<>("");
        canvas.setStatusMessageListener(status::set);

        click(canvas, screen(canvas, output));
        click(canvas, screen(canvas, input2));
        assertEquals("", status.get());
        Connectivity.Result afterSecond = Connectivity.analyze(
                schematic.pinPositions(), schematic.wires());
        assertEquals(Set.of(output, input1, input2),
                netFor(afterSecond, output).pins());
        assertEquals(Set.of(unrelatedOut, unrelatedIn),
                netFor(afterSecond, unrelatedOut).pins());

        List<WireSegment> beforeThirdRoute = schematic.wires();
        click(canvas, screen(canvas, output));
        click(canvas, screen(canvas, input3));
        assertEquals("", status.get());

        Connectivity.Result afterThird = Connectivity.analyze(
                schematic.pinPositions(), schematic.wires());
        assertEquals(Set.of(output, input1, input2, input3),
                netFor(afterThird, output).pins());
        assertEquals(Set.of(unrelatedOut, unrelatedIn),
                netFor(afterThird, unrelatedOut).pins());
        assertNotEquals(netFor(afterThird, output), netFor(afterThird, unrelatedOut));

        assertTrue(canvas.undo());
        assertEquals(beforeThirdRoute, schematic.wires(),
                "one undo removes the final branch and restores the exact previous geometry");
        Set<WireSegment> afterThirdGeometry = Set.copyOf(schematic.wires());
        assertTrue(canvas.undo());
        assertEquals(beforeThirdRoute, schematic.wires());
        assertTrue(canvas.redo());
        assertEquals(afterThirdGeometry, Set.copyOf(schematic.wires()));
    }

    @Test
    void pinThenWireClickCreatesInteriorTJunctionAtNearestGridPoint() {
        CanvasPanel canvas = wiredScene();
        Schematic schematic = canvas.schematic();
        Connectivity.PinPosition freeInput = freeInput(schematic);
        WireSegment targetWire = schematic.wires().getFirst();
        int clickX = (targetWire.x1() + targetWire.x2()) / 2 + 3;
        int clickY = targetWire.y1();
        Connectivity.Point attachment = new Connectivity.Point(
                Math.round(clickX / 10.0f) * 10, clickY);

        click(canvas, screen(canvas, freeInput));
        assertTrue(canvas.hasPendingAutoWireStart());
        move(canvas, screen(canvas, clickX, clickY));
        click(canvas, screen(canvas, clickX, clickY));

        Connectivity.Result topology = Connectivity.analyze(
                schematic.pinPositions(), schematic.wires());
        assertFalse(canvas.hasPendingAutoWireStart());
        assertTrue(topology.junctions().contains(attachment));
        assertEquals(Set.of(freeInput, pinOnExistingDriverNet(schematic)),
                netFor(topology, freeInput).pins());
    }

    @Test
    void wireThenPinClickCreatesInteriorTJunction() {
        CanvasPanel canvas = wiredScene();
        Schematic schematic = canvas.schematic();
        Connectivity.PinPosition freeInput = freeInput(schematic);
        WireSegment sourceWire = schematic.wires().getFirst();
        Connectivity.Point attachment = new Connectivity.Point(
                (sourceWire.x1() + sourceWire.x2()) / 2, sourceWire.y1());

        click(canvas, screen(canvas, attachment));
        assertTrue(canvas.hasPendingAutoWireStart());
        click(canvas, screen(canvas, freeInput));

        Connectivity.Result topology = Connectivity.analyze(
                schematic.pinPositions(), schematic.wires());
        assertFalse(canvas.hasPendingAutoWireStart());
        assertTrue(topology.junctions().contains(attachment));
        assertTrue(netFor(topology, freeInput).pins().contains(freeInput));
        assertTrue(netFor(topology, freeInput).pins().contains(pinOnExistingDriverNet(schematic)));
    }

    @Test
    void branchAtExistingWireEndpointAndExistingJunctionAddsSegmentsToThoseNodes() {
        CanvasPanel endpointCanvas = new CanvasPanel();
        endpointCanvas.setSize(900, 700);
        Schematic endpointSchematic = endpointCanvas.schematic();
        var target = endpointSchematic.add("output", 400, 250, Facing.EAST);
        Connectivity.PinPosition targetPin = pin(endpointSchematic, target.id(), "in0");
        WireSegment endpointNet = new WireSegment(100, 100, 200, 100);
        endpointSchematic.addWire(endpointNet);

        click(endpointCanvas, screen(endpointCanvas, 100, 100));
        assertTrue(endpointCanvas.hasPendingAutoWireStart());
        click(endpointCanvas, screen(endpointCanvas, targetPin));
        Connectivity.Result endpointTopology = Connectivity.analyze(
                endpointSchematic.pinPositions(), endpointSchematic.wires());
        assertTrue(netFor(endpointTopology, targetPin).segments().contains(endpointNet));
        assertEquals(1, endpointTopology.nets().size(),
                "the branch joins the endpoint's wire-only net");

        CanvasPanel junctionCanvas = new CanvasPanel();
        junctionCanvas.setSize(900, 700);
        Schematic junctionSchematic = junctionCanvas.schematic();
        var junctionTarget = junctionSchematic.add("output", 400, 300, Facing.EAST);
        Connectivity.PinPosition junctionTargetPin =
                pin(junctionSchematic, junctionTarget.id(), "in0");
        Connectivity.Point junction = new Connectivity.Point(200, 100);
        junctionSchematic.addWire(new WireSegment(100, 100, 300, 100));
        junctionSchematic.addWire(new WireSegment(200, 100, 200, 200));

        click(junctionCanvas, screen(junctionCanvas, junction));
        assertTrue(junctionCanvas.hasPendingAutoWireStart());
        click(junctionCanvas, screen(junctionCanvas, junctionTargetPin));
        Connectivity.Result junctionTopology = Connectivity.analyze(
                junctionSchematic.pinPositions(), junctionSchematic.wires());
        assertTrue(junctionTopology.junctions().contains(junction));
        assertTrue(netFor(junctionTopology, junctionTargetPin).pins().contains(junctionTargetPin));
        assertEquals(1, junctionTopology.nets().size());
    }

    private static CanvasPanel wiredScene() {
        CanvasPanel canvas = new CanvasPanel();
        canvas.setSize(900, 700);
        Schematic schematic = canvas.schematic();
        var driver = schematic.add("input", 100, 100, Facing.EAST);
        var firstSink = schematic.add("output", 300, 100, Facing.EAST);
        schematic.add("output", 500, 250, Facing.EAST);
        Connectivity.PinPosition output = pin(schematic, driver.id(), "out");
        Connectivity.PinPosition input = pin(schematic, firstSink.id(), "in0");
        schematic.addWire(new WireSegment(output.x(), output.y(), input.x(), input.y()));
        return canvas;
    }

    private static Connectivity.PinPosition pinOnExistingDriverNet(Schematic schematic) {
        Connectivity.Result topology = Connectivity.analyze(
                schematic.pinPositions(), schematic.wires());
        return topology.nets().stream()
                .filter(net -> net.segments().size() > 0)
                .flatMap(net -> net.pins().stream())
                .filter(pin -> pin.pinId().equals("out"))
                .findFirst().orElseThrow();
    }

    private static Connectivity.PinPosition freeInput(Schematic schematic) {
        return schematic.pinPositions().stream()
                .filter(pin -> schematic.components().stream()
                        .anyMatch(component -> component.id().equals(pin.componentId())
                                && component.typeId().equals("output")))
                .filter(pin -> schematic.wires().stream().noneMatch(wire ->
                        contains(wire, new Connectivity.Point(pin.x(), pin.y()))))
                .max(java.util.Comparator.comparingInt(Connectivity.PinPosition::y))
                .orElseThrow();
    }

    private static Connectivity.Net netFor(
            Connectivity.Result topology, Connectivity.PinPosition pin) {
        return topology.nets().stream()
                .filter(net -> net.pins().contains(pin))
                .findFirst().orElseThrow();
    }

    private static Connectivity.PinPosition pin(
            Schematic schematic, String componentId, String pinId) {
        return schematic.pinPositions().stream()
                .filter(pin -> pin.componentId().equals(componentId))
                .filter(pin -> pin.pinId().equals(pinId))
                .findFirst().orElseThrow();
    }

    private static boolean contains(WireSegment wire, Connectivity.Point point) {
        return wire.isHorizontal()
                ? point.y() == wire.y1() && point.x() >= wire.x1() && point.x() <= wire.x2()
                : point.x() == wire.x1() && point.y() >= wire.y1() && point.y() <= wire.y2();
    }

    private static Point screen(CanvasPanel canvas, Connectivity.PinPosition pin) {
        return screen(canvas, pin.x(), pin.y());
    }

    private static Point screen(CanvasPanel canvas, Connectivity.Point point) {
        return screen(canvas, point.x(), point.y());
    }

    private static Point screen(CanvasPanel canvas, int worldX, int worldY) {
        var point = canvas.viewTransform().worldToScreen(worldX, worldY);
        return new Point((int) Math.round(point.x), (int) Math.round(point.y));
    }

    private static void click(CanvasPanel canvas, Point point) {
        MouseEvent press = new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK,
                point.x, point.y, 1, false, MouseEvent.BUTTON1);
        for (var listener : canvas.getMouseListeners()) {
            listener.mousePressed(press);
        }
        MouseEvent release = new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(), 0,
                point.x, point.y, 1, false, MouseEvent.BUTTON1);
        for (var listener : canvas.getMouseListeners()) {
            listener.mouseReleased(release);
        }
    }

    private static void move(CanvasPanel canvas, Point point) {
        MouseEvent event = new MouseEvent(canvas, MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(), 0,
                point.x, point.y, 0, false, MouseEvent.NOBUTTON);
        for (var listener : canvas.getMouseMotionListeners()) {
            listener.mouseMoved(event);
        }
    }
}
