public class VerticalSegment {

    private final int id;
    private final int x;
    private final int yStart;
    private final int yEnd;
    private final double score;

    public VerticalSegment(
            int id,
            int x,
            int yStart,
            int yEnd,
            double score) {

        this.id = id;
        this.x = x;
        this.yStart = yStart;
        this.yEnd = yEnd;
        this.score = score;
    }

    public int getId() {
        return id;
    }

    public int getX() {
        return x;
    }

    public int getYStart() {
        return yStart;
    }

    public int getYEnd() {
        return yEnd;
    }

    public double getScore() {
        return score;
    }

    public int getLength() {
        return yEnd - yStart + 1;
    }
}