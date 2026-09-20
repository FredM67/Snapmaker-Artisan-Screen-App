package fabscreen.platform.base.obico;

public final class ObicoTemperature {
    private final String name;
    private final double actual;
    private final Double target;

    public ObicoTemperature(String name, double actual, Double target) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Temperature name is required");
        }
        this.name = name.trim();
        this.actual = finiteOrZero(actual);
        this.target = target == null ? null : finiteOrZero(target);
    }

    private static double finiteOrZero(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? 0.0d : value;
    }

    public String getName() {
        return name;
    }

    public double getActual() {
        return actual;
    }

    public Double getTarget() {
        return target;
    }
}
