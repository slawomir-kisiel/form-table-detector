import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class TableStructure {

    private final List<HorizontalSegment> horizontalLines;
    private final List<VerticalSegment> verticalLines;
    private final List<Intersection> intersections;
    private final List<HorizontalPairCandidate> rowCandidates;

    public TableStructure(
            List<HorizontalSegment> horizontalLines,
            List<VerticalSegment> verticalLines,
            List<Intersection> intersections,
            List<HorizontalPairCandidate> rowCandidates) {

        this.horizontalLines = immutableCopy(horizontalLines);
        this.verticalLines = immutableCopy(verticalLines);
        this.intersections = immutableCopy(intersections);
        this.rowCandidates = immutableCopy(rowCandidates);
    }

    public List<HorizontalSegment> getHorizontalLines() {
        return horizontalLines;
    }

    public List<VerticalSegment> getVerticalLines() {
        return verticalLines;
    }

    public List<Intersection> getIntersections() {
        return intersections;
    }

    public List<HorizontalPairCandidate> getRowCandidates() {
        return rowCandidates;
    }

    public Optional<HorizontalSegment> getHorizontalLine(int id) {
        return horizontalLines.stream()
                .filter(line -> line.getId() == id)
                .findFirst();
    }

    public Optional<VerticalSegment> getVerticalLine(int id) {
        return verticalLines.stream()
                .filter(line -> line.getId() == id)
                .findFirst();
    }

    public List<Intersection> getIntersectionsForHorizontal(int horizontalId) {
        List<Intersection> result = new ArrayList<>();

        for (Intersection intersection : intersections) {
            if (intersection.getHorizontalId() == horizontalId) {
                result.add(intersection);
            }
        }

        result.sort(
                Comparator.comparingInt(intersection ->
                        getVerticalLine(intersection.getVerticalId())
                                .map(VerticalSegment::getX)
                                .orElse(Integer.MAX_VALUE))
        );

        return Collections.unmodifiableList(result);
    }

    public List<Intersection> getIntersectionsForVertical(int verticalId) {
        List<Intersection> result = new ArrayList<>();

        for (Intersection intersection : intersections) {
            if (intersection.getVerticalId() == verticalId) {
                result.add(intersection);
            }
        }

        result.sort(
                Comparator.comparingInt(intersection ->
                        getHorizontalLine(intersection.getHorizontalId())
                                .map(HorizontalSegment::getY)
                                .orElse(Integer.MAX_VALUE))
        );

        return Collections.unmodifiableList(result);
    }

    public List<VerticalSegment> getVerticalLinesForHorizontal(int horizontalId) {
        List<VerticalSegment> result = new ArrayList<>();

        for (Intersection intersection : intersections) {
            if (intersection.getHorizontalId() != horizontalId) {
                continue;
            }

            getVerticalLine(intersection.getVerticalId())
                    .ifPresent(result::add);
        }

        result.sort(Comparator.comparingInt(VerticalSegment::getX));

        return Collections.unmodifiableList(result);
    }

    public List<HorizontalSegment> getHorizontalLinesForVertical(int verticalId) {
        List<HorizontalSegment> result = new ArrayList<>();

        for (Intersection intersection : intersections) {
            if (intersection.getVerticalId() != verticalId) {
                continue;
            }

            getHorizontalLine(intersection.getHorizontalId())
                    .ifPresent(result::add);
        }

        result.sort(Comparator.comparingInt(HorizontalSegment::getY));

        return Collections.unmodifiableList(result);
    }

    public List<VerticalSegment> getCommonVerticalLines(
            int horizontalId1,
            int horizontalId2) {

        List<VerticalSegment> result = new ArrayList<>();

        for (VerticalSegment vertical : verticalLines) {

            boolean connectedToFirst = false;
            boolean connectedToSecond = false;

            for (Intersection intersection : intersections) {

                if (intersection.getVerticalId() != vertical.getId()) {
                    continue;
                }

                if (intersection.getHorizontalId() == horizontalId1) {
                    connectedToFirst = true;
                }

                if (intersection.getHorizontalId() == horizontalId2) {
                    connectedToSecond = true;
                }

                if (connectedToFirst && connectedToSecond) {
                    break;
                }
            }

            if (connectedToFirst && connectedToSecond) {
                result.add(vertical);
            }
        }

        result.sort(Comparator.comparingInt(VerticalSegment::getX));

        return Collections.unmodifiableList(result);
    }

    public Optional<HorizontalPairCandidate> getBestRowCandidate() {
        return rowCandidates.stream()
                .max(Comparator.comparingDouble(
                        HorizontalPairCandidate::getScore));
    }

    public boolean isEmpty() {
        return horizontalLines.isEmpty();
    }

    public int getHorizontalLineCount() {
        return horizontalLines.size();
    }

    public int getVerticalLineCount() {
        return verticalLines.size();
    }

    public int getIntersectionCount() {
        return intersections.size();
    }

    private static <T> List<T> immutableCopy(List<T> source) {
        Objects.requireNonNull(source, "source");

        return Collections.unmodifiableList(
                new ArrayList<>(source));
    }

    @Override
    public String toString() {
        return "TableStructure{" +
                "horizontalLines=" + horizontalLines.size() +
                ", verticalLines=" + verticalLines.size() +
                ", intersections=" + intersections.size() +
                ", rowCandidates=" + rowCandidates.size() +
                '}';
    }
}