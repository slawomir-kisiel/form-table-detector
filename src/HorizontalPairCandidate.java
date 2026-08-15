import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class HorizontalPairCandidate {

    private final int topHorizontalId;
    private final int bottomHorizontalId;
    private final int height;
    private final List<Integer> verticalIds;

    private final double connectionScore;
    private final double heightScore;
    private final double score;

    public HorizontalPairCandidate(
            int topHorizontalId,
            int bottomHorizontalId,
            int height,
            List<Integer> verticalIds,
            double connectionScore,
            double heightScore,
            double score) {

        this.topHorizontalId = topHorizontalId;
        this.bottomHorizontalId = bottomHorizontalId;
        this.height = height;

        this.verticalIds = Collections.unmodifiableList(
                new ArrayList<>(verticalIds));

        this.connectionScore = connectionScore;
        this.heightScore = heightScore;
        this.score = score;
    }

    public int getTopHorizontalId() {
        return topHorizontalId;
    }

    public int getBottomHorizontalId() {
        return bottomHorizontalId;
    }

    public int getHeight() {
        return height;
    }

    public List<Integer> getVerticalIds() {
        return verticalIds;
    }

    public double getConnectionScore() {
        return connectionScore;
    }

    public double getHeightScore() {
        return heightScore;
    }

    public double getScore() {
        return score;
    }
}