package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.ComponentInstance;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.WireSegment;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasAutoWireTest {
    @Test
    void twoPinClicksRouteOneNetAndOneUndoStepWithoutMergingAnUnrelatedNet() {
        CanvasPanel canvas = new CanvasPanel();
        canvas.setSize(900, 700);
        Schematic schematic = canvas.schematic();
        var source = schematic.add("input", 100, 100, Facing.EAST);
        schematic.add("output", 200, 130, Facing.EAST);
        schematic.add("output", 350, 300, Facing.EAST);
        Connectivity.PinPosition sourcePin = pin(schematic, source.id(), "out");
        Connectivity.PinPosition targetPin = schematic.pinPositions().stream()
                .filter(pin -> !pin.componentId().equals(source.id()))
                .filter(pin -> pin.x() == 200 && pin.y() == 130)
                .findFirst().orElseThrow();
        Connectivity.PinPosition unrelatedPin = schematic.pinPositions().stream()
                .filter(pin -> pin.x() == 350 && pin.y() == 300)
                .findFirst().orElseThrow();

        click(canvas, screen(canvas, sourcePin));
        assertTrue(canvas.hasPendingAutoWireStart());
        move(canvas, screen(canvas, 160, 180));
        click(canvas, screen(canvas, targetPin));

        assertFalse(canvas.hasPendingAutoWireStart());
        assertTrue(schematic.wires().size() >= 2,
                "misaligned pins should produce a multi-segment orthogonal route");
        Connectivity.Result topology = Connectivity.analyze(
                schematic.pinPositions(), schematic.wires());
        Connectivity.Net connected = netFor(topology, sourcePin);
        assertEquals(Set.of(sourcePin, targetPin), connected.pins());
        assertNotEquals(connected, netFor(topology, unrelatedPin));

        assertTrue(canvas.canUndo());
        assertTrue(canvas.undo(), "the whole polyline is one undo step");
        assertTrue(schematic.wires().isEmpty());
        assertTrue(canvas.redo());
        assertFalse(schematic.wires().isEmpty());
    }

    @Test
    void escapeAndEmptyCanvasClickCancelThePendingFirstPin() {
        CanvasPanel canvas = new CanvasPanel();
        canvas.setSize(900, 700);
        var source = canvas.schematic().add("input", 100, 100, Facing.EAST);
        Connectivity.PinPosition sourcePin = pin(canvas.schematic(), source.id(), "out");

        click(canvas, screen(canvas, sourcePin));
        assertTrue(canvas.hasPendingAutoWireStart());
        click(canvas, screen(canvas, 500, 500));
        assertFalse(canvas.hasPendingAutoWireStart());
        assertTrue(canvas.schematic().wires().isEmpty());

        click(canvas, screen(canvas, sourcePin));
        assertTrue(canvas.hasPendingAutoWireStart());
        canvas.getActionMap().get("return-to-edit").actionPerformed(
                new java.awt.event.ActionEvent(
                        canvas, java.awt.event.ActionEvent.ACTION_PERFORMED, "escape"));
        assertFalse(canvas.hasPendingAutoWireStart());
        assertTrue(canvas.schematic().wires().isEmpty());
    }

    @Test
    void twoOutputPinsAreRefusedWithPlainStatusMessage() {
        CanvasPanel canvas = new CanvasPanel();
        canvas.setSize(900, 700);
        var first = canvas.schematic().add("input", 100, 100, Facing.EAST);
        var second = canvas.schematic().add("input", 250, 100, Facing.EAST);
        AtomicReference<String> status = new AtomicReference<>("");
        canvas.setStatusMessageListener(status::set);

        click(canvas, screen(canvas, pin(canvas.schematic(), first.id(), "out")));
        click(canvas, screen(canvas, pin(canvas.schematic(), second.id(), "out")));

        assertTrue(canvas.schematic().wires().isEmpty());
        assertEquals("Cannot connect two output pins (two drivers)", status.get());
    }

    @Test
    void twoUndrivenInputPinsMayBeConnected() {
        CanvasPanel canvas = new CanvasPanel();
        canvas.setSize(900, 700);
        var first = canvas.schematic().add("output", 100, 100, Facing.WEST);
        var second = canvas.schematic().add("output", 200, 100, Facing.EAST);
        Connectivity.PinPosition firstPin = pin(canvas.schematic(), first.id(), "in0");
        Connectivity.PinPosition secondPin = pin(canvas.schematic(), second.id(), "in0");

        click(canvas, screen(canvas, firstPin));
        click(canvas, screen(canvas, secondPin));

        assertFalse(canvas.schematic().wires().isEmpty());
        assertEquals(Set.of(firstPin, secondPin), netFor(
                Connectivity.analyze(canvas.schematic().pinPositions(), canvas.schematic().wires()),
                firstPin).pins());
    }

    @Test
    void inputToInputCanExtendAnAlreadyDrivenNetToAnotherSink() {
        CanvasPanel canvas = new CanvasPanel();
        canvas.setSize(900, 700);
        var driver = canvas.schematic().add("input", 100, 100, Facing.EAST);
        var drivenProbe = canvas.schematic().add("output", 200, 100, Facing.EAST);
        var freeProbe = canvas.schematic().add("output", 350, 200, Facing.EAST);
        Connectivity.PinPosition driverPin = pin(canvas.schematic(), driver.id(), "out");
        Connectivity.PinPosition drivenPin = pin(canvas.schematic(), drivenProbe.id(), "in0");
        Connectivity.PinPosition freePin = pin(canvas.schematic(), freeProbe.id(), "in0");
        canvas.schematic().addWire(new WireSegment(
                driverPin.x(), driverPin.y(), drivenPin.x(), drivenPin.y()));
        AtomicReference<String> status = new AtomicReference<>("");
        canvas.setStatusMessageListener(status::set);

        click(canvas, screen(canvas, drivenPin));
        click(canvas, screen(canvas, freePin));

        assertTrue(canvas.schematic().wires().size() > 1);
        Connectivity.Result topology = Connectivity.analyze(
                canvas.schematic().pinPositions(), canvas.schematic().wires());
        assertEquals(Set.of(driverPin, drivenPin, freePin),
                netFor(topology, driverPin).pins());
        assertEquals("", status.get());
    }

    @Test
    void alreadyDrivenInputCannotBeJoinedToAnotherOutputDriver() {
        CanvasPanel canvas = new CanvasPanel();
        canvas.setSize(900, 700);
        Schematic schematic = canvas.schematic();
        var firstDriver = schematic.add("input", 100, 100, Facing.EAST);
        var sink = schematic.add("output", 260, 100, Facing.EAST);
        var secondDriver = schematic.add("input", 100, 280, Facing.EAST);
        Connectivity.PinPosition firstOutput = pin(schematic, firstDriver.id(), "out");
        Connectivity.PinPosition drivenInput = pin(schematic, sink.id(), "in0");
        Connectivity.PinPosition secondOutput = pin(schematic, secondDriver.id(), "out");
        WireSegment existing = new WireSegment(
                firstOutput.x(), firstOutput.y(), drivenInput.x(), drivenInput.y());
        schematic.addWire(existing);
        AtomicReference<String> status = new AtomicReference<>("");
        canvas.setStatusMessageListener(status::set);

        click(canvas, screen(canvas, drivenInput));
        click(canvas, screen(canvas, secondOutput));

        assertEquals(List.of(existing), schematic.wires());
        assertEquals("Cannot connect nets with multiple output drivers", status.get());
    }

    @Test
    void rotatedDlatchOutputCanBranchToTwoFullAdderInputs() {
        CanvasPanel canvas = new CanvasPanel();
        canvas.setSize(1100, 900);
        Schematic schematic = canvas.schematic();
        ComponentDefinition latchDefinition = canvas.componentLibrary().get("DLATCH");
        ComponentDefinition faDefinition = canvas.componentLibrary().get("FA");
        ComponentInstance latch = schematic.addInstance(
                latchDefinition, 400, 300, Facing.NORTH);
        ComponentInstance firstAdder = schematic.addInstance(
                faDefinition, 100, 500, Facing.EAST);
        ComponentInstance secondAdder = schematic.addInstance(
                faDefinition, 700, 500, Facing.EAST);
        Connectivity.PinPosition q = pin(schematic, latch.id(), "Q");
        Connectivity.PinPosition firstA = pin(schematic, firstAdder.id(), "A");
        Connectivity.PinPosition secondA = pin(schematic, secondAdder.id(), "A");

        assertEquals(Facing.NORTH, canvas.endpointForPin(q).facing());
        assertTrue(canvas.endpointForPin(q).output());
        click(canvas, screen(canvas, q));
        click(canvas, screen(canvas, firstA));
        click(canvas, screen(canvas, q));
        click(canvas, screen(canvas, secondA));

        Connectivity.Result topology = Connectivity.analyze(
                schematic.pinPositions(), schematic.wires());
        assertEquals(Set.of(q, firstA, secondA), netFor(topology, q).pins());
        assertEquals(1, topology.nets().stream()
                .filter(net -> net.pins().contains(q))
                .count());
    }

    @Test
    void rotatedBoxPinMetadataUsesPortDirectionAndInstanceFacing() {
        CanvasPanel canvas = new CanvasPanel();
        Schematic schematic = canvas.schematic();
        ComponentDefinition definition = canvas.componentLibrary().get("DLATCH");
        ComponentInstance instance = schematic.addInstance(definition, 300, 200, Facing.NORTH);
        Connectivity.PinPosition input = pin(schematic, instance.id(), "D");
        Connectivity.PinPosition output = pin(schematic, instance.id(), "Q");

        AutoWireRouter.Endpoint inputEndpoint = canvas.endpointForPin(input);
        AutoWireRouter.Endpoint outputEndpoint = canvas.endpointForPin(output);

        assertEquals(Facing.NORTH, inputEndpoint.facing());
        assertFalse(inputEndpoint.output());
        assertEquals(Facing.NORTH, outputEndpoint.facing());
        assertTrue(outputEndpoint.output());
        assertEquals(input.x(), inputEndpoint.position().x());
        assertEquals(output.y(), outputEndpoint.position().y());
    }

    private static Connectivity.PinPosition pin(
            Schematic schematic, String componentId, String pinId) {
        return schematic.pinPositions().stream()
                .filter(pin -> pin.componentId().equals(componentId))
                .filter(pin -> pin.pinId().equals(pinId))
                .findFirst().orElseThrow();
    }

    private static Connectivity.Net netFor(
            Connectivity.Result topology, Connectivity.PinPosition pin) {
        return topology.nets().stream()
                .filter(net -> net.pins().contains(pin))
                .findFirst().orElseThrow();
    }

    private static Point screen(CanvasPanel canvas, Connectivity.PinPosition pin) {
        return screen(canvas, pin.x(), pin.y());
    }

    private static Point screen(CanvasPanel canvas, int worldX, int worldY) {
        var point = canvas.viewTransform().worldToScreen(worldX, worldY);
        return new Point((int) Math.round(point.x), (int) Math.round(point.y));
    }

    private static void click(CanvasPanel canvas, Point point) {
        press(canvas, point);
        release(canvas, point);
    }

    private static void press(CanvasPanel canvas, Point point) {
        MouseEvent event = new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK,
                point.x, point.y, 1, false, MouseEvent.BUTTON1);
        for (var listener : canvas.getMouseListeners()) {
            listener.mousePressed(event);
        }
    }

    private static void release(CanvasPanel canvas, Point point) {
        MouseEvent event = new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(), 0,
                point.x, point.y, 1, false, MouseEvent.BUTTON1);
        for (var listener : canvas.getMouseListeners()) {
            listener.mouseReleased(event);
        }
    }

    private static void move(CanvasPanel canvas, Point point) {
        MouseEvent event = new MouseEvent(canvas, MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(), 0,
                point.x, point.y, 0, false, MouseEvent.NOBUTTON);
        for (MouseMotionListener listener : canvas.getMouseMotionListeners()) {
            listener.mouseMoved(event);
        }
    }
}
