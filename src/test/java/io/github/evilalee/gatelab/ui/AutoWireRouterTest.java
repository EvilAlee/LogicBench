package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.WireSegment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoWireRouterTest {
    @Test
    void alignedPinsUseOneStraightSegment() {
        AutoWireRouter.Route route = AutoWireRouter.route(request(
                endpoint(0, 0, Facing.EAST, true),
                endpoint(100, 0, Facing.EAST, false)));

        assertFalse(route.usedFallback());
        assertEquals(List.of(new WireSegment(0, 0, 100, 0)), route.segments());
    }

    @Test
    void routesAroundComponentWithOneCellClearance() {
        AutoWireRouter.Request request = new AutoWireRouter.Request(
                endpoint(0, 0, Facing.EAST, true),
                endpoint(100, 0, Facing.EAST, false),
                List.of(new GateGeometry.Bounds(40, -10, 20, 20)),
                List.of(), List.of(), Set.of());

        AutoWireRouter.Route route = AutoWireRouter.route(request);
        assertFalse(route.usedFallback());
        assertTrue(route.segments().size() >= 3);
        for (Connectivity.Point point : points(route.segments())) {
            assertFalse(point.x() >= 30 && point.x() <= 70
                    && point.y() >= -20 && point.y() <= 20,
                    "route must clear the component and its one-cell margin");
        }
    }

    @Test
    void routesAroundExistingWireWithoutOverlapOrEndpointOnWire() {
        WireSegment obstacle = new WireSegment(40, 0, 60, 0);
        AutoWireRouter.Route route = AutoWireRouter.route(new AutoWireRouter.Request(
                endpoint(0, 0, Facing.EAST, true),
                endpoint(100, 0, Facing.EAST, false),
                List.of(), List.of(), List.of(obstacle), Set.of()));

        assertFalse(route.usedFallback());
        for (WireSegment segment : route.segments()) {
            assertFalse(overlapsCollinearly(segment, obstacle));
            assertFalse(contains(obstacle, new Connectivity.Point(segment.x1(), segment.y1())));
            assertFalse(contains(obstacle, new Connectivity.Point(segment.x2(), segment.y2())));
        }
    }

    @Test
    void crossesWireOnlyPerpendicularlyAtAnInteriorNonJunctionPoint() {
        WireSegment obstacle = new WireSegment(-200, 0, 200, 0);
        AutoWireRouter.Route route = AutoWireRouter.route(new AutoWireRouter.Request(
                endpoint(0, -50, Facing.SOUTH, true),
                endpoint(0, 50, Facing.SOUTH, false),
                List.of(), List.of(), List.of(obstacle), Set.of()));

        assertFalse(route.usedFallback());
        assertEquals(List.of(new WireSegment(0, -50, 0, 50)), route.segments());
        assertFalse(overlapsCollinearly(route.segments().getFirst(), obstacle));
    }

    @Test
    void routeDoesNotEndOnTheInteriorOfAnExistingWire() {
        WireSegment obstacle = new WireSegment(40, 50, 60, 50);
        AutoWireRouter.Route route = new AutoWireRouter.Route(
                AutoWireRouter.route(new AutoWireRouter.Request(
                        endpoint(0, 0, Facing.EAST, true),
                        endpoint(50, 50, Facing.EAST, false),
                        List.of(), List.of(), List.of(obstacle), Set.of())).segments(),
                AutoWireRouter.route(new AutoWireRouter.Request(
                        endpoint(0, 0, Facing.EAST, true),
                        endpoint(50, 50, Facing.EAST, false),
                        List.of(), List.of(), List.of(obstacle), Set.of())).usedFallback());

        assertTrue(route.usedFallback(),
                "a clean A* route cannot terminate on an existing wire interior");
    }

    @Test
    void minimizesBendsAndHonoursOutputExitAndInputEntryDirections() {
        AutoWireRouter.Route route = AutoWireRouter.route(request(
                endpoint(0, 0, Facing.EAST, true),
                endpoint(50, 30, Facing.EAST, false)));

        assertFalse(route.usedFallback());
        assertEquals(3, route.segments().size(), "two bends are sufficient and minimal");
        WireSegment first = route.segments().getFirst();
        WireSegment last = route.segments().getLast();
        assertTrue(first.isHorizontal() && first.x1() == 0 && first.x2() > 0,
                "the output leaves to its right");
        assertTrue(last.isHorizontal() && last.x2() == 50 && last.x1() < 50,
                "the input is entered from its left");
    }

    @Test
    void fallsBackToSimpleTwoBendRouteWhenEveryFirstStepIsBlocked() {
        AutoWireRouter.Route route = AutoWireRouter.route(new AutoWireRouter.Request(
                endpoint(0, 0, Facing.EAST, true),
                endpoint(100, 100, Facing.EAST, false),
                List.of(),
                List.of(new Connectivity.Point(10, 0), new Connectivity.Point(0, 10),
                        new Connectivity.Point(-10, 0), new Connectivity.Point(0, -10)),
                List.of(), Set.of()));

        assertTrue(route.usedFallback());
        assertEquals(3, route.segments().size());
    }

    private static AutoWireRouter.Request request(
            AutoWireRouter.Endpoint source, AutoWireRouter.Endpoint target) {
        return new AutoWireRouter.Request(source, target, List.of(), List.of(), List.of(), Set.of());
    }

    private static AutoWireRouter.Endpoint endpoint(
            int x, int y, Facing facing, boolean output) {
        return new AutoWireRouter.Endpoint(new Connectivity.Point(x, y), facing, output);
    }

    private static List<Connectivity.Point> points(List<WireSegment> segments) {
        java.util.LinkedHashSet<Connectivity.Point> result = new java.util.LinkedHashSet<>();
        for (WireSegment segment : segments) {
            if (segment.isHorizontal()) {
                for (int x = segment.x1(); x <= segment.x2(); x += 10) {
                    result.add(new Connectivity.Point(x, segment.y1()));
                }
            } else {
                for (int y = segment.y1(); y <= segment.y2(); y += 10) {
                    result.add(new Connectivity.Point(segment.x1(), y));
                }
            }
        }
        return List.copyOf(result);
    }

    private static boolean contains(WireSegment wire, Connectivity.Point point) {
        return wire.isHorizontal()
                ? point.y() == wire.y1() && point.x() >= wire.x1() && point.x() <= wire.x2()
                : point.x() == wire.x1() && point.y() >= wire.y1() && point.y() <= wire.y2();
    }

    private static boolean overlapsCollinearly(WireSegment first, WireSegment second) {
        if (first.isHorizontal() && second.isHorizontal() && first.y1() == second.y1()) {
            return Math.max(first.x1(), second.x1()) < Math.min(first.x2(), second.x2());
        }
        if (!first.isHorizontal() && !second.isHorizontal() && first.x1() == second.x1()) {
            return Math.max(first.y1(), second.y1()) < Math.min(first.y2(), second.y2());
        }
        return false;
    }
}
