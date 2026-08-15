public class Intersection {

    private final int horizontalId;
    private final int verticalId;
    private final IntersectionType type;
    private final double score;

    public Intersection(
            int horizontalId,
            int verticalId,
            IntersectionType type,
            double score) {

        this.horizontalId = horizontalId;
        this.verticalId = verticalId;
        this.type = type;
        this.score = score;
    }

    public int getHorizontalId() {
        return horizontalId;
    }

    public int getVerticalId() {
        return verticalId;
    }

    public IntersectionType getType() {
        return type;
    }

    public double getScore() {
        return score;
    }
}