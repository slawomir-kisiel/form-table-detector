public class HorizontalSegment {

    private final int id;
    private final int y;
    private final int xStart;
    private final int xEnd;
    private final double score;

    public HorizontalSegment(
            int id,
            int y,
            int xStart,
            int xEnd,
            double score) {

        this.id = id;
        this.y = y;
        this.xStart = xStart;
        this.xEnd = xEnd;
        this.score = score;
    }

    public int getId() {
        return id;
    }

    public int getY() {
        return y;
    }

    public int getXStart() {
        return xStart;
    }

    public int getXEnd() {
        return xEnd;
    }

    public double getScore() {
        return score;
    }

    public int getLength() {
        return xEnd - xStart + 1;
    }
}