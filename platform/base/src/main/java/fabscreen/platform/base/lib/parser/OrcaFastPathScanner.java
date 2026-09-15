package fabscreen.platform.base.lib.parser;

import java.util.Locale;

/**
 * Strictly recognizes the block layout emitted by Orca/Snapmaker Orca and performs the
 * minimum executable scan needed by the HMI's pre-print safety checks.
 *
 * <p>This scanner deliberately does not use the general G-code tokenizer. Within the executable
 * block it only tracks coordinate/extrusion modes, linear motion, tool selection and printable
 * object markers. All other commands are ignored. This keeps large files cheap to inspect while
 * retaining model bounds, tool usage and retraction information.</p>
 */
final class OrcaFastPathScanner {
    enum Action {
        DETAIL,
        EXECUTABLE,
        MARKER,
        INVALID
    }

    private enum Section {
        BEFORE_HEADER,
        HEADER,
        AFTER_HEADER,
        THUMBNAIL,
        AFTER_THUMBNAIL,
        EXECUTABLE,
        AFTER_EXECUTABLE,
        CONFIG,
        COMPLETE,
        INVALID
    }

    private static final String HEADER_START = "HEADER_BLOCK_START";
    private static final String HEADER_END = "HEADER_BLOCK_END";
    private static final String THUMBNAIL_START = "THUMBNAIL_BLOCK_START";
    private static final String THUMBNAIL_END = "THUMBNAIL_BLOCK_END";
    private static final String EXECUTABLE_START = "EXECUTABLE_BLOCK_START";
    private static final String EXECUTABLE_END = "EXECUTABLE_BLOCK_END";
    private static final String CONFIG_START = "CONFIG_BLOCK_START";
    private static final String CONFIG_END = "CONFIG_BLOCK_END";

    private Section section = Section.BEFORE_HEADER;
    private int physicalLineCount;
    private int executableLineCount;
    private boolean sawNonBlankBeforeHeader;

    private boolean xyzRelative;
    private boolean xyzModeKnown;
    private boolean extruderRelative;
    private boolean extruderModeKnown;
    private boolean unitsMillimetersKnown;
    private boolean xyArcPlane;
    private boolean arcPlaneKnown;
    private float x;
    private float y;
    private float z;
    private boolean xKnown;
    private boolean yKnown;
    private boolean zKnown;
    // Artisan Marlin stores one logical E coordinate in current_position.e. Tool changes select
    // the motor but do not swap this coordinate; G92 E therefore applies across T0/T1.
    private float extrusionPosition;
    private boolean extrusionPositionKnown;
    private int activeTool;
    private boolean activeToolKnown;
    private boolean tool0Defined;
    private boolean tool1Defined;
    // Logical positive-E use and whole-job physical use are deliberately separate from model
    // boundary deposition. A nozzle that only purges/primes, or operates through M605 clone,
    // mirror, or backup mode, must still participate in compatibility checks.
    private boolean tool0Used;
    private boolean tool1Used;
    private boolean physicalTool0Used;
    private boolean physicalTool1Used;
    private boolean objectBoundaryStarted;
    private boolean objectBoundaryActive;
    private boolean sawDepositedMove;
    private boolean unsupportedMotion;
    private boolean invalidNumber;

    private float minX = Float.MAX_VALUE;
    private float maxX = -Float.MAX_VALUE;
    private float minY = Float.MAX_VALUE;
    private float maxY = -Float.MAX_VALUE;
    private float minZ = Float.MAX_VALUE;
    private float maxZ = -Float.MAX_VALUE;

    private Float observedRetraction0;
    private Float observedRetraction1;
    private Float configuredRetraction0;
    private Float configuredRetraction1;
    private Float switchRetraction0;
    private Float switchRetraction1;
    private int customPrintMode;

    Action consumeLine(String line) {
        physicalLineCount++;
        if (section == Section.INVALID) return Action.INVALID;

        String marker = blockMarker(line);
        if (marker != null) {
            return consumeMarker(marker);
        }

        switch (section) {
            case BEFORE_HEADER:
                if (!isBlank(line)) {
                    sawNonBlankBeforeHeader = true;
                    section = Section.INVALID;
                    return Action.INVALID;
                }
                return Action.MARKER;
            case HEADER:
            case AFTER_HEADER:
            case THUMBNAIL:
            case AFTER_THUMBNAIL:
            case AFTER_EXECUTABLE:
            case CONFIG:
                if (!isBlank(line) && commentText(line) == null) {
                    section = Section.INVALID;
                    return Action.INVALID;
                }
                consumeRetractionMetadata(line);
                return Action.DETAIL;
            case EXECUTABLE:
                executableLineCount++;
                consumeExecutableLine(line);
                return Action.EXECUTABLE;
            case COMPLETE:
                // Orca may append whitespace/comments after CONFIG_BLOCK_END. They are detail
                // comments, not another executable section.
                if (!isBlank(line) && commentText(line) == null) {
                    section = Section.INVALID;
                    return Action.INVALID;
                }
                return Action.DETAIL;
            default:
                section = Section.INVALID;
                return Action.INVALID;
        }
    }

    private Action consumeMarker(String marker) {
        boolean valid;
        switch (section) {
            case BEFORE_HEADER:
                valid = !sawNonBlankBeforeHeader && HEADER_START.equals(marker);
                if (valid) section = Section.HEADER;
                break;
            case HEADER:
                valid = HEADER_END.equals(marker);
                if (valid) section = Section.AFTER_HEADER;
                break;
            case AFTER_HEADER:
                valid = THUMBNAIL_START.equals(marker);
                if (valid) section = Section.THUMBNAIL;
                break;
            case THUMBNAIL:
                valid = THUMBNAIL_END.equals(marker);
                if (valid) section = Section.AFTER_THUMBNAIL;
                break;
            case AFTER_THUMBNAIL:
                valid = EXECUTABLE_START.equals(marker);
                if (valid) section = Section.EXECUTABLE;
                break;
            case EXECUTABLE:
                valid = EXECUTABLE_END.equals(marker);
                if (valid) section = Section.AFTER_EXECUTABLE;
                break;
            case AFTER_EXECUTABLE:
                valid = CONFIG_START.equals(marker);
                if (valid) section = Section.CONFIG;
                break;
            case CONFIG:
                valid = CONFIG_END.equals(marker);
                if (valid) section = Section.COMPLETE;
                break;
            default:
                valid = false;
                break;
        }
        if (!valid) {
            section = Section.INVALID;
            return Action.INVALID;
        }
        return Action.MARKER;
    }

    private void consumeExecutableLine(String line) {
        if (line == null) return;
        int start = skipWhitespace(line, 0);
        if (start >= line.length()) return;
        if (line.charAt(start) == ';') {
            String comment = line.substring(start);
            if (GcodeParser.isPrintableObjectBoundaryStop(comment)) {
                objectBoundaryActive = false;
            } else if (GcodeParser.isPrintableObjectBoundaryStart(comment)) {
                if (!objectBoundaryStarted) resetBoundary();
                objectBoundaryStarted = true;
                objectBoundaryActive = true;
            }
            return;
        }

        char family = upper(line.charAt(start));
        int commandEnd = start + 1;
        while (commandEnd < line.length() && Character.isDigit(line.charAt(commandEnd))) {
            commandEnd++;
        }
        if (commandEnd == start + 1) return;
        if (commandEnd < line.length() && line.charAt(commandEnd) == '.') {
            unsupportedMotion = true;
            return;
        }
        int code = parseUnsignedInteger(line, start + 1, commandEnd);
        if (code < 0) return;

        if (family == 'N') {
            // Line-number/checksum framing can contain another command on the same line.
            unsupportedMotion = true;
            return;
        }

        if (family == 'T') {
            if (code == 0) {
                applyToolChange(0);
                tool0Defined = true;
            } else if (code == 1) {
                applyToolChange(1);
                tool1Defined = true;
            } else {
                unsupportedMotion = true;
            }
            return;
        }
        if (family == 'M') {
            if (code == 82) {
                extruderRelative = false;
                extruderModeKnown = true;
            } else if (code == 83) {
                extruderRelative = true;
                extruderModeKnown = true;
            }
            else if (code == 605) applyPrintMode(line, commandEnd);
            else if (code == 200) unsupportedMotion = true;
            return;
        }
        if (family != 'G') {
            // Modal coordinate-only records (for example X10Y10E1) inherit the previous
            // motion command. They need additional state before they can be scanned safely.
            if (family == 'X' || family == 'Y' || family == 'Z' || family == 'E') {
                unsupportedMotion = true;
            }
            return;
        }

        if (code == 90) {
            xyzRelative = false;
            xyzModeKnown = true;
            // Marlin's G90/G91 set the E mode as well; a later M82/M83 may override it.
            extruderRelative = false;
            extruderModeKnown = true;
            return;
        }
        if (code == 91) {
            xyzRelative = true;
            xyzModeKnown = true;
            extruderRelative = true;
            extruderModeKnown = true;
            return;
        }
        if (code == 20) {
            // The legacy parser does not convert inches. Force its existing fallback instead of
            // claiming a millimetre safety boundary for an unsupported coordinate unit.
            unsupportedMotion = true;
            return;
        }
        if (code == 17) {
            xyArcPlane = true;
            arcPlaneKnown = true;
            return;
        }
        if (code == 18 || code == 19) {
            xyArcPlane = false;
            arcPlaneKnown = true;
            return;
        }
        if (code == 21) {
            unitsMillimetersKnown = true;
            return;
        }
        if ((code >= 53 && code <= 59) || code == 68 || code == 69) {
            unsupportedMotion = true;
            return;
        }
        if (code == 10 || code == 11) {
            // Firmware retraction changes extrusion state without an explicit E delta. Keep
            // such files on the established full parser until that state is modelled here.
            unsupportedMotion = true;
            return;
        }
        if (code == 28) {
            // A home position depends on machine configuration and cannot safely be guessed from
            // the file. Absolute moves/G92 may re-establish each axis before model deposition;
            // otherwise the fast result is rejected below.
            xKnown = false;
            yKnown = false;
            zKnown = false;
            // Artisan Marlin's G28 restores the workspace plane to XY.
            xyArcPlane = true;
            arcPlaneKnown = true;
            return;
        }
        if (code == 92) {
            applyCoordinateReset(line, commandEnd);
            return;
        }
        if (code == 2 || code == 3) {
            if (!arcPlaneKnown || !xyArcPlane) {
                unsupportedMotion = true;
                return;
            }
            applyArcMove(line, commandEnd, code == 2);
            return;
        }
        if (code == 5) {
            // A spline changes the modal XYZ position even when it does not extrude.
            unsupportedMotion = true;
            return;
        }
        if (code == 4 || code == 80 || code == 94) return;
        if (code != 0 && code != 1) {
            // Stay strict: an unmodelled G command may move, restore, offset, or otherwise alter
            // the coordinate state used by a later axis-omitting extrusion (for example G60/G61).
            unsupportedMotion = true;
            return;
        }
        // Marlin permits extrusion on both G0 and G1. Object-boundary safety must therefore
        // treat a positive-E G0 as deposited material too.
        applyLinearMove(line, commandEnd, true);
    }

    private void applyToolChange(int nextTool) {
        if (!activeToolKnown || nextTool != activeTool) {
            // Artisan Marlin applies the calibrated hotend-offset delta to current_position on a
            // real tool transition. The file doesn't carry those per-machine values, so require
            // subsequent absolute coordinates before trusting another deposited point.
            xKnown = false;
            yKnown = false;
            zKnown = false;
        }
        activeTool = nextTool;
        activeToolKnown = true;
    }

    private void applyCoordinateReset(String line, int offset) {
        ParameterCursor cursor = new ParameterCursor(line, offset);
        while (cursor.next()) {
            if (!cursor.valid) {
                invalidNumber = true;
                return;
            }
            switch (cursor.letter) {
                case 'X':
                    x = cursor.value;
                    xKnown = unitsMillimetersKnown || cursor.value == 0f;
                    break;
                case 'Y':
                    y = cursor.value;
                    yKnown = unitsMillimetersKnown || cursor.value == 0f;
                    break;
                case 'Z':
                    z = cursor.value;
                    zKnown = unitsMillimetersKnown || cursor.value == 0f;
                    break;
                case 'E':
                    extrusionPosition = cursor.value;
                    extrusionPositionKnown = unitsMillimetersKnown || cursor.value == 0f;
                    break;
                default: break;
            }
        }
    }

    private void applyLinearMove(String line, int offset, boolean depositingCommand) {
        float nextX = x;
        float nextY = y;
        float nextZ = z;
        boolean nextXKnown = xKnown;
        boolean nextYKnown = yKnown;
        boolean nextZKnown = zKnown;
        float currentE = extrusionPosition;
        float nextE = currentE;
        boolean nextEKnown = extrusionPositionKnown;
        boolean hasX = false;
        boolean hasY = false;
        boolean hasZ = false;
        boolean hasE = false;
        float extrusionDelta = 0f;

        ParameterCursor cursor = new ParameterCursor(line, offset);
        while (cursor.next()) {
            if (!cursor.valid) {
                invalidNumber = true;
                return;
            }
            switch (cursor.letter) {
                case 'X':
                    if (xyzModeKnown && unitsMillimetersKnown) {
                        nextX = xyzRelative ? x + cursor.value : cursor.value;
                        nextXKnown = !xyzRelative || xKnown;
                    } else {
                        nextXKnown = false;
                    }
                    hasX = true;
                    break;
                case 'Y':
                    if (xyzModeKnown && unitsMillimetersKnown) {
                        nextY = xyzRelative ? y + cursor.value : cursor.value;
                        nextYKnown = !xyzRelative || yKnown;
                    } else {
                        nextYKnown = false;
                    }
                    hasY = true;
                    break;
                case 'Z':
                    if (xyzModeKnown && unitsMillimetersKnown) {
                        nextZ = xyzRelative ? z + cursor.value : cursor.value;
                        nextZKnown = !xyzRelative || zKnown;
                    } else {
                        nextZKnown = false;
                    }
                    hasZ = true;
                    break;
                case 'E':
                    if (!extruderModeKnown) {
                        unsupportedMotion = true;
                        nextEKnown = false;
                    } else if (!unitsMillimetersKnown) {
                        if (objectBoundaryActive) unsupportedMotion = true;
                        nextEKnown = false;
                    } else if (extruderRelative) {
                        extrusionDelta = cursor.value;
                        if (extrusionPositionKnown) nextE = currentE + cursor.value;
                    } else {
                        if (!extrusionPositionKnown) unsupportedMotion = true;
                        else extrusionDelta = cursor.value - currentE;
                        nextE = cursor.value;
                        nextEKnown = true;
                    }
                    hasE = true;
                    break;
                default:
                    break;
            }
        }

        boolean moved = (hasX && nextX != x) || (hasY && nextY != y) || (hasZ && nextZ != z);
        if (hasE && !activeToolKnown) unsupportedMotion = true;
        if (hasE && extrusionDelta > 0.000001f && activeToolKnown) {
            markActiveToolUsed();
        }
        if (hasE && extrusionDelta < 0f) {
            if (extrusionDelta > -8f) {
                observeRetraction(activeTool, -extrusionDelta);
            } else if (objectBoundaryActive) {
                // Large unload/end-script moves are intentionally ignored outside the object,
                // but an in-model value must never be hidden behind a small configured value.
                unsupportedMotion = true;
            }
        }
        if (depositingCommand && hasE && extrusionDelta > 0.000001f
                && objectBoundaryActive) {
            if (!xKnown || !yKnown || !zKnown
                    || !nextXKnown || !nextYKnown || !nextZKnown) {
                unsupportedMotion = true;
            } else {
                if (moved) updateBoundary(x, y, z);
                updateBoundary(nextX, nextY, nextZ);
                sawDepositedMove = true;
            }
        }

        x = nextX;
        y = nextY;
        z = nextZ;
        xKnown = nextXKnown;
        yKnown = nextYKnown;
        zKnown = nextZKnown;
        extrusionPosition = nextE;
        extrusionPositionKnown = nextEKnown;
    }

    private void applyArcMove(String line, int offset, boolean clockwise) {
        float startX = x;
        float startY = y;
        float startZ = z;
        float nextX = x;
        float nextY = y;
        float nextZ = z;
        boolean nextXKnown = xKnown;
        boolean nextYKnown = yKnown;
        boolean nextZKnown = zKnown;
        float currentE = extrusionPosition;
        float nextE = currentE;
        boolean nextEKnown = extrusionPositionKnown;
        Float iOffset = null;
        Float jOffset = null;
        Float radius = null;
        boolean hasE = false;
        float extrusionDelta = 0f;

        ParameterCursor cursor = new ParameterCursor(line, offset);
        while (cursor.next()) {
            if (!cursor.valid) {
                invalidNumber = true;
                return;
            }
            switch (cursor.letter) {
                case 'X':
                    if (xyzModeKnown && unitsMillimetersKnown) {
                        nextX = xyzRelative ? x + cursor.value : cursor.value;
                        nextXKnown = !xyzRelative || xKnown;
                    } else {
                        nextXKnown = false;
                    }
                    break;
                case 'Y':
                    if (xyzModeKnown && unitsMillimetersKnown) {
                        nextY = xyzRelative ? y + cursor.value : cursor.value;
                        nextYKnown = !xyzRelative || yKnown;
                    } else {
                        nextYKnown = false;
                    }
                    break;
                case 'Z':
                    if (xyzModeKnown && unitsMillimetersKnown) {
                        nextZ = xyzRelative ? z + cursor.value : cursor.value;
                        nextZKnown = !xyzRelative || zKnown;
                    } else {
                        nextZKnown = false;
                    }
                    break;
                case 'I': iOffset = cursor.value; break;
                case 'J': jOffset = cursor.value; break;
                case 'R': radius = cursor.value; break;
                case 'E':
                    if (!extruderModeKnown) {
                        unsupportedMotion = true;
                        nextEKnown = false;
                    } else if (!unitsMillimetersKnown) {
                        if (objectBoundaryActive) unsupportedMotion = true;
                        nextEKnown = false;
                    } else if (extruderRelative) {
                        extrusionDelta = cursor.value;
                        if (extrusionPositionKnown) nextE = currentE + cursor.value;
                    } else {
                        if (!extrusionPositionKnown) unsupportedMotion = true;
                        else extrusionDelta = cursor.value - currentE;
                        nextE = cursor.value;
                        nextEKnown = true;
                    }
                    hasE = true;
                    break;
                default: break;
            }
        }

        if (!xKnown || !yKnown || !zKnown
                || !nextXKnown || !nextYKnown || !nextZKnown) {
            unsupportedMotion = true;
            return;
        }
        float[] center = resolveArcCenter(startX, startY, nextX, nextY,
                iOffset, jOffset, radius, clockwise);
        if (center == null) {
            // Marlin rejects an arc with no usable offset/radius instead of adopting its endpoint.
            // Do not let such a command poison the coordinate state used by a later extrusion.
            unsupportedMotion = true;
            return;
        }

        if (hasE && !activeToolKnown) unsupportedMotion = true;
        if (hasE && extrusionDelta > 0.000001f && activeToolKnown) {
            markActiveToolUsed();
        }
        if (hasE && extrusionDelta < 0f) {
            if (extrusionDelta > -8f) {
                observeRetraction(activeTool, -extrusionDelta);
            } else if (objectBoundaryActive) {
                unsupportedMotion = true;
            }
        }
        boolean depositing = hasE && extrusionDelta > 0.000001f && objectBoundaryActive;
        if (depositing) {
            updateArcBoundary(startX, startY, startZ, nextX, nextY, nextZ,
                    center[0], center[1], clockwise);
            sawDepositedMove = true;
        }
        x = nextX;
        y = nextY;
        z = nextZ;
        xKnown = nextXKnown;
        yKnown = nextYKnown;
        zKnown = nextZKnown;
        extrusionPosition = nextE;
        extrusionPositionKnown = nextEKnown;
    }

    private float[] resolveArcCenter(float startX, float startY, float endX, float endY,
                                     Float iOffset, Float jOffset, Float signedRadius,
                                     boolean clockwise) {
        boolean hasOffset = iOffset != null || jOffset != null;
        if (hasOffset == (signedRadius != null)) return null;

        float[] center;
        if (hasOffset) {
            float i = iOffset == null ? 0f : iOffset;
            float j = jOffset == null ? 0f : jOffset;
            if (i == 0f && j == 0f) return null;
            center = new float[]{startX + i, startY + j};
        } else {
            center = centerFromRadius(startX, startY, endX, endY,
                    signedRadius, clockwise);
            if (center == null) return null;
        }

        double startRadius = Math.hypot(startX - center[0], startY - center[1]);
        double endRadius = Math.hypot(endX - center[0], endY - center[1]);
        if (Double.isNaN(startRadius) || Double.isInfinite(startRadius)
                || Double.isNaN(endRadius) || Double.isInfinite(endRadius)
                || startRadius <= 0d
                || Math.abs(startRadius - endRadius)
                > Math.max(0.05d, startRadius * 0.01d)) {
            return null;
        }
        return center;
    }

    private void applyPrintMode(String line, int offset) {
        ParameterCursor cursor = new ParameterCursor(line, offset);
        while (cursor.next()) {
            if (!cursor.valid) {
                invalidNumber = true;
                return;
            }
            if (cursor.letter == 'S') {
                customPrintMode = Math.round(cursor.value);
            }
        }
    }

    private void updateArcBoundary(float startX, float startY, float startZ,
                                   float endX, float endY, float endZ,
                                   float centerX, float centerY, boolean clockwise) {
        updateBoundary(startX, startY, startZ);
        updateBoundary(endX, endY, endZ);
        double radius = Math.hypot(startX - centerX, startY - centerY);
        double endRadius = Math.hypot(endX - centerX, endY - centerY);
        if (Double.isNaN(radius) || Double.isInfinite(radius)
                || Double.isNaN(endRadius) || Double.isInfinite(endRadius)
                || radius <= 0d || Math.abs(radius - endRadius) > Math.max(0.05d, radius * 0.01d)) {
            unsupportedMotion = true;
            return;
        }
        double startAngle = Math.atan2(startY - centerY, startX - centerX);
        double endAngle = Math.atan2(endY - centerY, endX - centerX);
        boolean fullCircle = Math.hypot(endX - startX, endY - startY) <= 0.000001d;
        double[] cardinal = {0d, Math.PI / 2d, Math.PI, Math.PI * 3d / 2d};
        for (double angle : cardinal) {
            if (fullCircle || angleOnSweep(angle, startAngle, endAngle, clockwise)) {
                float arcX = (float) (centerX + radius * Math.cos(angle));
                float arcY = (float) (centerY + radius * Math.sin(angle));
                // Z changes linearly along a helical arc; its extrema remain at the endpoints.
                updateBoundary(arcX, arcY, startZ);
                updateBoundary(arcX, arcY, endZ);
            }
        }
    }

    private static boolean angleOnSweep(double angle, double start, double end,
                                        boolean clockwise) {
        double twoPi = Math.PI * 2d;
        angle = normalizeAngle(angle, twoPi);
        start = normalizeAngle(start, twoPi);
        end = normalizeAngle(end, twoPi);
        if (clockwise) {
            double sweep = normalizeAngle(start - end, twoPi);
            double candidate = normalizeAngle(start - angle, twoPi);
            return candidate <= sweep + 1e-9d;
        }
        double sweep = normalizeAngle(end - start, twoPi);
        double candidate = normalizeAngle(angle - start, twoPi);
        return candidate <= sweep + 1e-9d;
    }

    private static double normalizeAngle(double value, double twoPi) {
        value %= twoPi;
        return value < 0d ? value + twoPi : value;
    }

    private static float[] centerFromRadius(float startX, float startY,
                                            float endX, float endY,
                                            float signedRadius, boolean clockwise) {
        double dx = endX - startX;
        double dy = endY - startY;
        double chord = Math.hypot(dx, dy);
        double radius = Math.abs((double) signedRadius);
        if (chord <= 0d || chord > radius * 2d) return null;
        double midpointX = (startX + endX) / 2d;
        double midpointY = (startY + endY) / 2d;
        double height = Math.sqrt(Math.max(0d, radius * radius - chord * chord / 4d));
        double normalX = -dy / chord;
        double normalY = dx / chord;
        // Marlin's signed-R convention selects the minor arc for positive R and major arc for
        // negative R. Choose the corresponding side of the chord for the commanded direction.
        double side = (clockwise ? -1d : 1d) * (signedRadius < 0f ? -1d : 1d);
        return new float[]{
                (float) (midpointX + normalX * height * side),
                (float) (midpointY + normalY * height * side)
        };
    }

    private void observeRetraction(int tool, float distance) {
        if (!isFinite(distance) || distance < 0f) return;
        if (tool == 1) {
            observedRetraction1 = observedRetraction1 == null
                    ? distance : Math.max(observedRetraction1, distance);
        } else {
            observedRetraction0 = observedRetraction0 == null
                    ? distance : Math.max(observedRetraction0, distance);
        }
    }

    private void markActiveToolUsed() {
        if (activeTool == 1) {
            tool1Used = true;
            physicalTool1Used = true;
        } else {
            tool0Used = true;
            physicalTool0Used = true;
        }
        // Capture the mode at the actual extrusion point. A later M605 S0 must not erase that
        // the backup/clone/mirror mode already operated both physical nozzles.
        if (duplicatesOrBacksUpTool()) {
            physicalTool0Used = true;
            physicalTool1Used = true;
        }
    }

    private boolean duplicatesOrBacksUpTool() {
        // Artisan M605 S1 (backup), S2 (clone), and S3 (mirror) can make either physical
        // hotend operational even when the executable selects only one logical tool.
        return customPrintMode >= 1 && customPrintMode <= 3;
    }

    private boolean isTool0EffectivelyUsed() {
        return physicalTool0Used;
    }

    private boolean isTool1EffectivelyUsed() {
        return physicalTool1Used;
    }

    private void consumeRetractionMetadata(String line) {
        String comment = commentText(line);
        if (comment == null) return;
        int delimiter = comment.indexOf('=');
        if (delimiter < 0) return;
        String key = comment.substring(0, delimiter).trim().toLowerCase(Locale.US);
        String value = comment.substring(delimiter + 1).trim();
        if (key.equals("retraction_length")) {
            Float[] values = parseFloatList(value);
            if (values != null && values.length > 0) {
                configuredRetraction0 = values[0];
                if (values.length > 1) configuredRetraction1 = values[1];
            } else {
                invalidNumber = true;
            }
        } else if (key.equals("retract_length_toolchange")) {
            Float[] values = parseFloatList(value);
            if (values != null && values.length > 0) {
                switchRetraction0 = values[0];
                if (values.length > 1) switchRetraction1 = values[1];
            } else {
                invalidNumber = true;
            }
        }
    }

    boolean isStructurallyComplete() {
        return section == Section.COMPLETE;
    }

    /**
     * Returns whether every executable construct was understood well enough to prove that an
     * otherwise-unused physical nozzle never extrudes. A full legacy fallback may add positive
     * tool-use evidence, but it must not turn an incomplete fast scan into a negative proof.
     */
    boolean hasComprehensiveToolUsageResult() {
        return isStructurallyComplete() && !unsupportedMotion && !invalidNumber;
    }

    boolean hasTrustedSafetyResult() {
        if (!isStructurallyComplete() || unsupportedMotion || invalidNumber
                || !objectBoundaryStarted || !sawDepositedMove) return false;
        if (!isFinite(minX) || !isFinite(maxX) || !isFinite(minY) || !isFinite(maxY)
                || !isFinite(minZ) || !isFinite(maxZ)) return false;
        if (minX > maxX || minY > maxY || minZ > maxZ) return false;
        boolean usesTool0 = isTool0EffectivelyUsed();
        boolean usesTool1 = isTool1EffectivelyUsed();
        if (usesTool0 && !isValidRetraction(configuredRetraction0)) return false;
        if (usesTool1 && !isValidRetraction(configuredRetraction1)) return false;
        if (!retractionConsistent(observedRetraction0, configuredRetraction0, usesTool0)) return false;
        if (!retractionConsistent(observedRetraction1, configuredRetraction1, usesTool1)) return false;
        return true;
    }

    private boolean isValidRetraction(Float value) {
        return value != null && isFinite(value) && value >= 0f && value <= 100f;
    }

    private boolean retractionConsistent(Float observed, Float configured, boolean used) {
        if (!used || observed == null) return true;
        if (!isValidRetraction(observed) || !isValidRetraction(configured)) return false;
        float tolerance = Math.max(0.05f, configured * 0.25f);
        return Math.abs(observed - configured) <= tolerance;
    }

    int getPhysicalLineCount() { return physicalLineCount; }
    int getExecutableLineCount() { return executableLineCount; }
    float getMinX() { return minX; }
    float getMaxX() { return maxX; }
    float getMinY() { return minY; }
    float getMaxY() { return maxY; }
    float getMinZ() { return minZ; }
    float getMaxZ() { return maxZ; }
    boolean isTool0Defined() { return tool0Defined || isTool0EffectivelyUsed(); }
    boolean isTool1Defined() { return tool1Defined || isTool1EffectivelyUsed(); }
    boolean isTool0ExplicitlyUsed() { return tool0Used; }
    boolean isTool1ExplicitlyUsed() { return tool1Used; }
    boolean isTool0Used() { return isTool0EffectivelyUsed(); }
    boolean isTool1Used() { return isTool1EffectivelyUsed(); }
    float getRetraction0() {
        float observed = observedRetraction0 == null ? 0f : observedRetraction0;
        if (isTool0EffectivelyUsed() && configuredRetraction0 != null) {
            return Math.max(configuredRetraction0, observed);
        }
        return observed;
    }
    float getRetraction1() {
        float observed = observedRetraction1 == null ? 0f : observedRetraction1;
        if (isTool1EffectivelyUsed() && configuredRetraction1 != null) {
            return Math.max(configuredRetraction1, observed);
        }
        return observed;
    }
    float getSwitchRetraction0() {
        return switchRetraction0 == null ? 0f : switchRetraction0;
    }
    float getSwitchRetraction1() {
        return switchRetraction1 == null ? 0f : switchRetraction1;
    }
    int getCustomPrintMode() { return customPrintMode; }

    private void updateBoundary(float px, float py, float pz) {
        if (!isFinite(px) || !isFinite(py) || !isFinite(pz)) {
            invalidNumber = true;
            return;
        }
        minX = Math.min(minX, px);
        maxX = Math.max(maxX, px);
        minY = Math.min(minY, py);
        maxY = Math.max(maxY, py);
        minZ = Math.min(minZ, pz);
        maxZ = Math.max(maxZ, pz);
    }

    private void resetBoundary() {
        minX = Float.MAX_VALUE;
        maxX = -Float.MAX_VALUE;
        minY = Float.MAX_VALUE;
        maxY = -Float.MAX_VALUE;
        minZ = Float.MAX_VALUE;
        maxZ = -Float.MAX_VALUE;
        sawDepositedMove = false;
    }

    private static String blockMarker(String line) {
        String comment = commentText(line);
        if (comment == null) return null;
        String marker = comment.trim().toUpperCase(Locale.US);
        return marker.endsWith("_BLOCK_START") || marker.endsWith("_BLOCK_END")
                ? marker : null;
    }

    private static String commentText(String line) {
        if (line == null) return null;
        int start = skipWhitespace(line, 0);
        if (start >= line.length() || line.charAt(start) != ';') return null;
        return line.substring(start + 1).trim();
    }

    private static boolean isBlank(String line) {
        return line == null || skipWhitespace(line, 0) == line.length();
    }

    private static int skipWhitespace(String value, int offset) {
        while (offset < value.length()) {
            char c = value.charAt(offset);
            if (c != ' ' && c != '\t' && c != '\r') break;
            offset++;
        }
        return offset;
    }

    private static int parseUnsignedInteger(String value, int start, int end) {
        if (start >= end) return -1;
        int parsed = 0;
        for (int index = start; index < end; index++) {
            char c = value.charAt(index);
            if (c < '0' || c > '9') return -1;
            parsed = parsed * 10 + (c - '0');
        }
        return parsed;
    }

    private static boolean containsParameter(String line, int offset, char expected) {
        ParameterCursor cursor = new ParameterCursor(line, offset);
        while (cursor.next()) if (cursor.letter == expected) return true;
        return false;
    }

    private static Float[] parseFloatList(String value) {
        String[] parts = value.split(",", -1);
        Float[] parsed = new Float[parts.length];
        try {
            for (int index = 0; index < parts.length; index++) {
                float item = Float.parseFloat(parts[index].trim());
                if (!isFinite(item) || item < 0f || item > 100f) return null;
                parsed[index] = item;
            }
            return parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static char upper(char value) {
        return value >= 'a' && value <= 'z' ? (char) (value - ('a' - 'A')) : value;
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    /** Allocation-free parameter iterator for the handful of safety-relevant commands. */
    private static final class ParameterCursor {
        private final String line;
        private int index;
        char letter;
        float value;
        boolean valid;

        ParameterCursor(String line, int index) {
            this.line = line;
            this.index = index;
        }

        boolean next() {
            int length = line.length();
            while (index < length && (line.charAt(index) == ' ' || line.charAt(index) == '\t')) {
                index++;
            }
            if (index >= length || line.charAt(index) == ';') return false;
            char parameter = line.charAt(index);
            if (!((parameter >= 'A' && parameter <= 'Z')
                    || (parameter >= 'a' && parameter <= 'z'))) {
                valid = false;
                return true;
            }

            letter = upper(parameter);
            index++;
            boolean negative = false;
            if (index < length && (line.charAt(index) == '+' || line.charAt(index) == '-')) {
                negative = line.charAt(index) == '-';
                index++;
            }
            boolean digit = false;
            boolean overflow = false;
            double parsed = 0d;
            while (index < length && line.charAt(index) >= '0' && line.charAt(index) <= '9') {
                parsed = parsed * 10d + (line.charAt(index) - '0');
                if (Double.isInfinite(parsed) || parsed > Float.MAX_VALUE) overflow = true;
                index++;
                digit = true;
            }
            if (index < length && line.charAt(index) == '.') {
                index++;
                double fraction = 0.1d;
                while (index < length && line.charAt(index) >= '0' && line.charAt(index) <= '9') {
                    if (!overflow && fraction > 0d) {
                        parsed += (line.charAt(index) - '0') * fraction;
                        fraction *= 0.1d;
                    }
                    index++;
                    digit = true;
                }
            }
            if (!digit) {
                valid = false;
                return true;
            }
            if (negative) parsed = -parsed;
            value = (float) parsed;
            valid = !overflow && isFinite(value);
            return true;
        }
    }
}
