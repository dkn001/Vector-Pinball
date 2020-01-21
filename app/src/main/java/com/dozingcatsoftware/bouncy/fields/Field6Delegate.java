package com.dozingcatsoftware.bouncy.fields;

import com.badlogic.gdx.math.Vector2;
import com.dozingcatsoftware.bouncy.Ball;
import com.dozingcatsoftware.bouncy.BaseFieldDelegate;
import com.dozingcatsoftware.bouncy.Color;
import com.dozingcatsoftware.bouncy.Field;
import com.dozingcatsoftware.bouncy.elements.DropTargetGroupElement;
import com.dozingcatsoftware.bouncy.elements.RolloverGroupElement;
import com.dozingcatsoftware.bouncy.elements.SensorElement;
import com.dozingcatsoftware.bouncy.elements.WallElement;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class Field6Delegate extends BaseFieldDelegate {
    private static final double TAU = 2 * Math.PI;

    private enum MultiballStatus {INACTIVE, STARTING, ACTIVE}
    private enum PlanetStatus {OFF, IN_PROGRESS, ON}

    private final double sunGravityForce = 8.0;
    private final double planetGravityForce = 15.0; // multiplied by radius^3 for each planet.
    // The sun and planets are this far "below" the ball.
    private final double gravityDepthSquared = 2.0 * 2.0;
    // Distance from sun beyond which gravity is not applied.
    private final double gravityRangeSquared = 8.0 * 8.0;

    private final long rampBonusDurationNanos = 10_000_000_000L;
    private long rampBonusNanosRemaining = 0;
    private int rampBonusMultiplier = 1;

    private final long rampScore = 2500;
    private final long planet1TargetsScore = 5000;
    private final long planet2RolloversScore = 5000;
    private final long planetActivatedScore = 5000;
    private final long multiballJackpotScore = 100000;

    private MultiballStatus multiballStatus = MultiballStatus.INACTIVE;
    private int multiballJackpotMultiplier = 1;

    private static final Color BLACK = Color.fromRGB(0, 0, 0);
    private final List<Color> planetColors = Arrays.asList(
            Color.fromRGB(0xFF, 0x55, 0x00),
            Color.fromRGB(0x00, 0x99, 0xFF),
            Color.fromRGB(0x99, 0x00, 0x00),
            Color.fromRGB(0x00, 0xAA, 0x66),
            Color.fromRGB(0xAA, 0x22, 0xCC));
    private final List<Color> ballColors = Arrays.asList(
            Color.fromRGB(0xFF, 0x99, 0x44),
            Color.fromRGB(0x77, 0xCC, 0xFF),
            Color.fromRGB(0xCC, 0x66, 0x66),
            Color.fromRGB(0x77, 0xCC, 0xAA),
            Color.fromRGB(0xCC, 0x88, 0xEE));
    private final List<Color> ballSecondaryColors = Arrays.asList(
            Color.fromRGB(0xCC, 0x77, 0x22),
            Color.fromRGB(0x55, 0xAA, 0xCC),
            Color.fromRGB(0xAA, 0x44, 0x44),
            Color.fromRGB(0x55, 0xAA, 0x88),
            Color.fromRGB(0xAA, 0x66, 0xCC));

    private final class Planet {
        RolloverGroupElement element;
        Color color;
        double radius;
        double angle;
        double angularVelocity;
        PlanetStatus status;
    }

    private Planet[] planets;
    private double inProgressPlanetPhase = 0;

    private WallElement launchBarrier;
    private RolloverGroupElement orbits;
    private RolloverGroupElement sun;

    private void startMultiball(final Field field) {
        ((WallElement) field.getFieldElementById("BallSaver-left")).setRetracted(false);
        ((WallElement) field.getFieldElementById("BallSaver-right")).setRetracted(false);
        for (Planet p : planets) {
            p.status = PlanetStatus.OFF;
        }
        // "Starting" state until the last ball is launched so we don't exit multiball until then.
        multiballStatus = MultiballStatus.STARTING;
        multiballJackpotMultiplier = 1;
        field.showGameMessage("Multiball!", 4000);
        field.scheduleAction(1000, new Runnable() {
            @Override public void run() {
                field.launchBall();
            }
        });
        field.scheduleAction(1000, new Runnable() {
            @Override public void run() {
                field.launchBall();
                multiballStatus = MultiballStatus.ACTIVE;
            }
        });
    }

    private void endMultiball(Field field) {
        multiballStatus = MultiballStatus.INACTIVE;
        for (Planet p : planets) {
            p.status = PlanetStatus.OFF;
        }
    }

    private boolean allPlanetsOn() {
        for (Planet p : planets) {
            if (p.status != PlanetStatus.ON) {
                return false;
            }
        }
        return true;
    }

    private void activatePlanetIfMatch(Field field, Ball ball, int planetIndex) {
        if (planets[planetIndex].status == PlanetStatus.ON) {
            return;
        }
        int ballColorIndex = ballColors.indexOf(ball.getPrimaryColor());
        if (ballColorIndex == planetIndex) {
            planets[planetIndex].status = PlanetStatus.ON;
            field.addScore(planetActivatedScore);
            if (allPlanetsOn()) {
                if (multiballStatus == MultiballStatus.INACTIVE) {
                    startMultiball(field);
                }
                else {
                    String prefix = (multiballJackpotMultiplier > 1) ?
                            (multiballJackpotMultiplier + "x ") : "";
                    String msg = prefix + "Jackpot!";
                    field.showGameMessage(msg, 2000);
                    field.addScore(multiballJackpotScore * multiballJackpotMultiplier);
                    multiballJackpotMultiplier += 1;
                    for (Planet p : planets) {
                        p.status = PlanetStatus.OFF;
                    }
                }
            }
            else {
                field.showGameMessage("Planet " + (planetIndex + 1) + " Activated!", 1500);
            }
        }
    }

    private void checkRamp(
            Field field, Ball ball, String prevSensorId, long points, Integer planetIndex) {
        if (prevSensorId.equals(ball.getPreviousSensorId())) {
            if (rampBonusMultiplier > 1) {
                field.showGameMessage(rampBonusMultiplier + "x Ramp", 1000);
            }
            rampBonusNanosRemaining = rampBonusDurationNanos;
            field.addScore(points * rampBonusMultiplier);
            rampBonusMultiplier += 1;
            if (planetIndex != null) {
                activatePlanetIfMatch(field, ball, planetIndex);
            }
        }
    }

    private boolean anyBallHasColorForPlanetIndex(Field field, int planetIndex) {
        Color color = ballColors.get(planetIndex);
        List<Ball> balls = field.getBalls();
        for (int i = 0; i < balls.size(); i++) {
            if (balls.get(i).getPrimaryColor().equals(color)) {
                return true;
            }
        }
        return false;
    }

    private void updatePlanetStatus(Field field, long nanos) {
        inProgressPlanetPhase += TAU * (nanos / 4e9);
        while (inProgressPlanetPhase > TAU) {
            inProgressPlanetPhase -= TAU;
        }
        for (int i = 0; i < planets.length; i++) {
            Planet p = planets[i];
            if (p.status != PlanetStatus.ON) {
                p.status = anyBallHasColorForPlanetIndex(field, i) ?
                        PlanetStatus.IN_PROGRESS : PlanetStatus.OFF;
            }
            p.element.setAllRolloversActivated(p.status != PlanetStatus.OFF);
            // In-progress planets cycle between 30% and 100% of their full color.
            double phase = (p.status == PlanetStatus.IN_PROGRESS) ?
                    (1 + Math.sin(inProgressPlanetPhase)) * 0.35 : 0;
            p.element.setRolloverColorAtIndex(0, p.color.blendedWith(BLACK, phase));
        }
    }

    @Override public void gameStarted(Field field) {
        launchBarrier = (WallElement) field.getFieldElementById("LaunchBarrier");
        launchBarrier.setRetracted(true);

        sun = (RolloverGroupElement) field.getFieldElementById("Sun");
        sun.setAllRolloversActivated(true);
        orbits = (RolloverGroupElement) field.getFieldElementById("Orbits");

        int numPlanets = orbits.numberOfRollovers();
        planets = new Planet[numPlanets];
        Random rand = new Random();
        for (int i = 0; i < numPlanets; i++) {
            Planet p = new Planet();
            planets[i] = p;
            p.element = (RolloverGroupElement) field.getFieldElementById("Planet" + (i + 1));
            p.radius = p.element.getRolloverRadiusAtIndex(0);
            p.color = planetColors.get(i);
            p.angle = rand.nextDouble() * TAU;
            // Planets closer to the sun have larger angular velocities.
            p.angularVelocity = (0.9 + 0.2 * rand.nextDouble()) / (i + 1);
            p.status = PlanetStatus.OFF;
        }
    }

    @Override public boolean isFieldActive(Field field) {
        // Planets should orbit even when there is no active ball.
        return true;
    }

    @Override public void ballLost(Field field) {
        launchBarrier.setRetracted(false);
    }

    @Override public void ballInSensorRange(Field field, SensorElement sensor, Ball ball) {
        String sensorId = sensor.getElementId();
        if ("LaunchBarrierSensor".equals(sensorId)) {
            launchBarrier.setRetracted(false);
        }
        else if ("LaunchBarrierRetract".equals(sensorId)) {
            launchBarrier.setRetracted(true);
        }
        else if ("LeftLoopDetector_Trigger".equals(sensorId)) {
            checkRamp(field, ball, "LeftLoopDetector_Enter", rampScore, 3);
        }
        else if ("RightLoopDetector_Trigger".equals(sensorId)) {
            checkRamp(field, ball, "RightLoopDetector_Enter", rampScore, 2);
        }
        else if ("OrbitDetector_Left".equals(sensorId)) {
            checkRamp(field, ball, "OrbitDetector_Right", rampScore, 4);
        }
        else if ("OrbitDetector_Right".equals(sensorId)) {
            checkRamp(field, ball, "OrbitDetector_Left", rampScore, 4);
        }
    }

    @Override public void allDropTargetsInGroupHit(
            Field field, DropTargetGroupElement targetGroup, Ball ball) {
        String id = targetGroup.getElementId();
        if ("DropTargetLeftSave".equals(id)) {
            ((WallElement) field.getFieldElementById("BallSaver-left")).setRetracted(false);
            field.showGameMessage("Left Save Enabled", 1500);
        }
        else if ("DropTargetRightSave".equals(id)) {
            ((WallElement) field.getFieldElementById("BallSaver-right")).setRetracted(false);
            field.showGameMessage("Right Save Enabled", 1500);
        }
        else if ("Planet1Targets".equals(id)) {
            field.addScore(planet1TargetsScore);
            activatePlanetIfMatch(field, ball, 0);
        }
    }

    @Override public void allRolloversInGroupActivated(
            Field field, RolloverGroupElement group, Ball ball) {
        String id = group.getElementId();
        if ("FlipperRollovers".equals(id)) {
            group.setAllRolloversActivated(false);
            field.incrementScoreMultiplier();
            field.showGameMessage(((int) field.getScoreMultiplier()) + "x Multiplier", 1500);
        }
        else if ("Planet2Rollovers".equals(id)) {
            group.setAllRolloversActivated(false);
            field.addScore(planet2RolloversScore);
            activatePlanetIfMatch(field, ball, 1);
        }
        else {
            int planetIndex = -1;
            for (int i = 0; i < planets.length; i++) {
                if (group == planets[i].element) {
                    planetIndex = i;
                    break;
                }
            }
            if (planetIndex >= 0) {
                ball.setPrimaryColor(ballColors.get(planetIndex));
                ball.setSecondaryColor(ballSecondaryColors.get(planetIndex));
                // Planet statuses will be updated in tick().
            }
        }
    }

    @Override public void tick(Field field, long nanos) {
        if (planets == null) {
            return;
        }
        // Check for exiting multiball.
        if (field.getBalls().size()<=1 && multiballStatus == MultiballStatus.ACTIVE) {
            endMultiball(field);
        }
        // Sync planet states with active balls.
        updatePlanetStatus(field, nanos);
        // Update ramp multiplier.
        if (rampBonusNanosRemaining > 0) {
            rampBonusNanosRemaining -= nanos;
        }
        if (rampBonusNanosRemaining <= 0) {
            rampBonusMultiplier = 1;
        }
        // Move planets.
        double dt = nanos / 1e9;
        for (int i = 0; i < planets.length; i++) {
            Planet p = planets[i];
            p.angle += dt * p.angularVelocity;
            while (p.angle > TAU) {
                p.angle -= TAU;
            }
            while (p.angle < 0) {
                p.angle += TAU;
            }
            Vector2 orbitCenter = orbits.getRolloverCenterAtIndex(i);
            double orbitRadius = orbits.getRolloverRadiusAtIndex(i);
            double px = orbitCenter.x + orbitRadius * Math.cos(p.angle);
            double py = orbitCenter.y + orbitRadius * Math.sin(p.angle);
            p.element.setRolloverCenterAtIndex(0, px, py);
        }
        // Apply gravity.
        List<Ball> balls = field.getBalls();
        for (int i = 0; i < balls.size(); i++) {
            Ball ball = balls.get(i);
            Vector2 ballPos = ball.getPosition();
            Vector2 sunPos = sun.getRolloverCenterAtIndex(0);
            double sdx = sunPos.x - ballPos.x;
            double sdy = sunPos.y - ballPos.y;
            double sunDistSq = sdx * sdx + sdy * sdy;
            if (sunDistSq <= gravityRangeSquared) {
                double sunAngle = Math.atan2(sdy, sdx);
                double sunForce = sunGravityForce / (gravityDepthSquared + sunDistSq);
                double forceX = sunForce * Math.cos(sunAngle);
                double forceY = sunForce * Math.sin(sunAngle);
                for (Planet planet : planets) {
                    Vector2 planetPos = planet.element.getRolloverCenterAtIndex(0);
                    double mass = Math.pow(planet.radius, 3);
                    double pdx = planetPos.x - ballPos.x;
                    double pdy = planetPos.y - ballPos.y;
                    double planetDistSq = pdx * pdx + pdy * pdy;
                    double planetAngle = Math.atan2(pdy, pdx);
                    double planetForce =
                            planetGravityForce * mass / (gravityDepthSquared + planetDistSq);
                    forceX += planetForce * Math.cos(planetAngle);
                    forceY += planetForce * Math.sin(planetAngle);
                }
                ball.applyLinearImpulse(new Vector2((float)(dt * forceX), (float)(dt * forceY)));
            }
        }
    }
}
