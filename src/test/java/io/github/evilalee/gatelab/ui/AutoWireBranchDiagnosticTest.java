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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Baseline reproduction for the follow-up branch-attachment work.
 *
 * These tests intentionally describe the current failure modes; they do not
 * change production routing or click handling.
 */
class AutoWireBranchDiagnosticTest {
    @Test
    void drivenOutputFanOutPassesDriveCheckButFallsBackBecauseItsOwnNetBlocksTheFirstCell() {
        CanvasPanel canvas = new CanvasPanel();
        canvas.setSize(900, 700);
        Schematic schematic = canvas.schematic();
        var source = schematic.add("input", 100, 100, Facing.EAST);
        var firstSink = schematic.add("output", 240, 100, Facing.EAST);
        var secondSink = schematic.add("output", 100, 250, Facing.EAST);
        Connectivity.PinPosition output = pin(schematic, source.id(), "out");
        Connectivity.PinPosition firstInput = pin(schematic, firstSink.id(), "in0");
        Connectivity.PinPosition secondInput = pin(schematic, secondSink.id(), "in0");
        WireSegment existing = new WireSegment(
                output.x(), output.y(), firstInput.x(), firstInput.y());
        schematic.addWire(existing);

        Connectivity.Result before = Connectivity.analyze(
                schematic.pinPositions(), schematic.wires());
        Connectivity.Net sourceNet = netFor(before, output);
        Connectivity.Net targetNet = netFor(before, secondInput);
        assertEquals(1, driverCount(canvas, sourceNet));
        assertEquals(0, driverCount(canvas, targetNet));

        AutoWireRouter.Route route = AutoWireRouter.route(requestFor(
                canvas, output, secondInput, before));
        assertTrue(route.usedFallback(),
                "the preferred eastward first cell is on the source's own horizontal net; "
                        + "the source body's clearance blocks the perpendicular escape cells");

        AtomicReference<String> status = new AtomicReference<>("");
        canvas.setStatusMessageListener(status::set);
        click(canvas, screen(canvas, output));
        click(canvas, screen(canvas, secondInput));

        assertEquals(2, schematic.wires().size());
        assertEquals("Could not find a clean route; drew a direct wire", status.get(),
                "the net-based drive check accepts one driver feeding another input; "
                        + "the route falls back instead of reporting a driver refusal");
        Connectivity.Result after = Connectivity.analyze(
                schematic.pinPositions(), schematic.wires());
        assertEquals(Set.of(output, firstInput, secondInput),
                netFor(after, output).pins());
    }

    @Test
    void clickingAWireAsTheSecondEndpointCancelsThePendingPinRoute() {
        CanvasPanel canvas = wiredScene();
        Connectivity.PinPosition freeInput = freeInput(canvas.schematic());
        WireSegment targetWire = canvas.schematic().wires().getFirst();
        int wireCount = canvas.schematic().wires().size();

        click(canvas, screen(canvas, freeInput));
        assertTrue(canvas.hasPendingAutoWireStart());
        click(canvas, screen(canvas, midpoint(targetWire)));

        assertFalse(canvas.hasPendingAutoWireStart(),
                "beginEditGesture clears the pin start when the second hit is a wire, not a pin");
        assertEquals(wireCount, canvas.schematic().wires().size());
        assertEquals(List.of(targetWire), canvas.selectedWires());
    }

    @Test
    void clickingAWireFirstSelectsItInsteadOfArmingAWireAttachment() {
        CanvasPanel canvas = wiredScene();
        Connectivity.PinPosition freeInput = freeInput(canvas.schematic());
        WireSegment targetWire = canvas.schematic().wires().getFirst();
        int wireCount = canvas.schematic().wires().size();

        click(canvas, screen(canvas, midpoint(targetWire)));
        assertFalse(canvas.hasPendingAutoWireStart(),
                "a wire-first click follows the ordinary wire selection gesture");
        assertEquals(List.of(targetWire), canvas.selectedWires());

        click(canvas, screen(canvas, freeInput));
        assertTrue(canvas.hasPendingAutoWireStart(),
                "the following pin click arms a new pin start, rather than completing wire-to-pin");
        assertEquals(wireCount, canvas.schematic().wires().size());
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

    private static AutoWireRouter.Request requestFor(
            CanvasPanel canvas,
            Connectivity.PinPosition source,
            Connectivity.PinPosition target,
            Connectivity.Result topology) {
        Schematic schematic = canvas.schematic();
        List<GateGeometry.Bounds> bounds = new ArrayList<>();
        for (PlacedComponent component : schematic.components()) {
            GateGeometry.Bounds relative = GateGeometry.bounds(
                    schematic.definitionFor(component), component.facing());
            bounds.add(new GateGeometry.Bounds(
                    component.x() + relative.x(), component.y() + relative.y(),
                    relative.width(), relative.height()));
        }
        List<Connectivity.Point> otherPins = schematic.pinPositions().stream()
                .filter(pin -> !pin.equals(source) && !pin.equals(target))
                .map(pin -> new Connectivity.Point(pin.x(), pin.y()))
                .toList();
        return new AutoWireRouter.Request(
                canvas.endpointForPin(source), canvas.endpointForPin(target), bounds,
                otherPins, schematic.wires(), topology.junctions());
    }

    private static long driverCount(CanvasPanel canvas, Connectivity.Net net) {
        return net.pins().stream()
                .filter(pin -> canvas.endpointForPin(pin).output())
                .count();
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

    private static Connectivity.Point midpoint(WireSegment wire) {
        return new Connectivity.Point(
                (wire.x1() + wire.x2()) / 2, (wire.y1() + wire.y2()) / 2);
    }

    private static boolean contains(WireSegment wire, Connectivity.Point point) {
        return wire.isHorizontal()
                ? point.y() == wire.y1() && point.x() >= wire.x1() && point.x() <= wire.x2()
                : point.x() == wire.x1() && point.y() >= wire.y1() && point.y() <= wire.y2();
    }

    private static Point screen(CanvasPanel canvas, Connectivity.PinPosition pin) {
        var point = canvas.viewTransform().worldToScreen(pin.x(), pin.y());
        return new Point((int) Math.round(point.x), (int) Math.round(point.y));
    }

    private static Point screen(CanvasPanel canvas, Connectivity.Point world) {
        var point = canvas.viewTransform().worldToScreen(world.x(), world.y());
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
}
