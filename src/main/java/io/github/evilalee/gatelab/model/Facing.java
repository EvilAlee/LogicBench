package io.github.evilalee.gatelab.model;

/** Quarter-turn orientations in clockwise order for a coordinate system with Y down. */
public enum Facing {
    EAST(0.0),
    SOUTH(Math.PI / 2.0),
    WEST(Math.PI),
    NORTH(-Math.PI / 2.0);

    private final double rotationRadians;

    Facing(double rotationRadians) {
        this.rotationRadians = rotationRadians;
    }

    public double rotationRadians() {
        return rotationRadians;
    }

    public Facing clockwise() {
        return values()[(ordinal() + 1) % values().length];
    }
}
