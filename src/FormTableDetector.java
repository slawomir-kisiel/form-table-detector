import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Wykrywa strukturę linii tabeli/formularza na zeskanowanym obrazie.
 *
 * Założenia:
 * - obraz został wcześniej wyprostowany (deskew),
 * - threshold został wcześniej wyznaczony,
 * - piksele poza obrazem traktowane są jako białe,
 * - linie mogą zawierać krótkie przerwy wynikające ze skanowania,
 * - lewej i/lub prawej zewnętrznej krawędzi tabeli może nie być,
 * - narożniki mogą być zaokrąglone, więc linia pozioma i pionowa
 *   nie muszą stykać się dokładnie w jednym pikselu,
 * - pionowe linie są wyszukiwane wyłącznie jako linie wychodzące
 *   od znalezionych wcześniej linii poziomych.
 */
public abstract class FormTableDetector {

    /**
     * Istniejąca implementacja użytkownika.
     *
     * Powinna określać, czy piksel RGB jest ciemny według podanego threshold.
     */
    protected abstract boolean isDark(int rgb, int threshold);


    // ========================================================================
    // PUBLIC API
    // ========================================================================

    public TableStructure detect(
            BufferedImage image,
            int threshold,
            int maxDistortion,
            Options options) {

        if (image == null) {
            throw new IllegalArgumentException("image must not be null");
        }

        if (maxDistortion < 0) {
            throw new IllegalArgumentException(
                    "maxDistortion must be >= 0");
        }

        if (options == null) {
            options = new Options();
        }

        validateOptions(options);

        /*
         * ETAP 1
         *
         * Wykrycie wszystkich sensownych poziomych segmentów.
         */
        List<HorizontalSegment> horizontal =
                findHorizontalSegments(
                        image,
                        threshold,
                        maxDistortion,
                        options);

        /*
         * ETAP 2
         *
         * Pionowe segmenty są wyszukiwane wyłącznie w górę i w dół
         * od znalezionych linii poziomych.
         */
        List<VerticalSegment> vertical =
                findVerticalSegments(
                        image,
                        threshold,
                        maxDistortion,
                        horizontal,
                        options);

        /*
         * ETAP 3
         *
         * Budowa informacji o przecięciach / połączeniach.
         */
        List<Intersection> intersections =
                findIntersections(
                        horizontal,
                        vertical,
                        options.connectionTolerance);

        /*
         * ETAP 4
         *
         * Kandydaci na relacje pomiędzy dwiema liniami poziomymi.
         * Nie wybieramy tutaj jeszcze arbitralnie jednej pary.
         */
        List<HorizontalPairCandidate> rowCandidates =
                buildHorizontalPairCandidates(
                        horizontal,
                        vertical,
                        intersections,
                        options);

        return new TableStructure(
                horizontal,
                vertical,
                intersections,
                rowCandidates);
    }


    // ========================================================================
    // HORIZONTAL DETECTION
    // ========================================================================

    private List<HorizontalSegment> findHorizontalSegments(
            BufferedImage image,
            int threshold,
            int maxDistortion,
            Options options) {

        List<HorizontalSegment> detected =
                new ArrayList<>();

        int width = image.getWidth();
        int height = image.getHeight();

        int nextId = 0;

        /*
         * Dla każdego bazowego Y tworzymy histogram/sygnał wzdłuż X.
         */
        for (int baseY = 0; baseY < height; baseY++) {

            int startX = -1;
            int lastCandidateX = -1;

            int gap = 0;

            double scoreSum = 0.0;
            int scoreCount = 0;

            for (int x = 0; x < width; x++) {

                PointScore point =
                        horizontalPointScore(
                                image,
                                x,
                                baseY,
                                threshold,
                                maxDistortion,
                                options);

                boolean candidate =
                        isPointCandidate(point, options);

                if (candidate) {

                    if (startX < 0) {
                        startX = x;
                        scoreSum = 0.0;
                        scoreCount = 0;
                    }

                    lastCandidateX = x;
                    gap = 0;

                    scoreSum += point.score;
                    scoreCount++;

                } else if (startX >= 0) {

                    gap++;

                    /*
                     * Dopóki przerwa nie jest większa niż GAP,
                     * pozostajemy w obrębie tej samej linii.
                     *
                     * Dzięki temu np.:
                     *
                     * 1 0 1 0 1 0 1 0 1
                     *
                     * nadal może być jednym segmentem.
                     */
                    if (gap > options.maxHorizontalGap) {

                        nextId = addHorizontalSegment(
                                image,
                                threshold,
                                maxDistortion,
                                options,
                                detected,
                                nextId,
                                baseY,
                                startX,
                                lastCandidateX,
                                scoreSum,
                                scoreCount);

                        startX = -1;
                        lastCandidateX = -1;
                        gap = 0;
                        scoreSum = 0.0;
                        scoreCount = 0;
                    }
                }
            }

            /*
             * Segment dochodzący do prawej krawędzi obrazu.
             */
            if (startX >= 0) {

                nextId = addHorizontalSegment(
                        image,
                        threshold,
                        maxDistortion,
                        options,
                        detected,
                        nextId,
                        baseY,
                        startX,
                        lastCandidateX,
                        scoreSum,
                        scoreCount);
            }
        }

        /*
         * Ta sama fizyczna linia będzie zwykle znaleziona dla kilku
         * sąsiednich wartości baseY.
         */
        detected =
                mergeHorizontalSegments(
                        detected,
                        options,
                        maxDistortion);

        /*
         * Po scaleniu jeszcze raz poprawiamy Y na podstawie całej
         * długości ostatecznego segmentu.
         */
        for (HorizontalSegment segment : detected) {

            segment.y =
                    refineHorizontalY(
                            image,
                            threshold,
                            segment.xStart,
                            segment.xEnd,
                            segment.y,
                            maxDistortion);
        }

        detected.sort(
                Comparator
                        .comparingInt((HorizontalSegment h) -> h.y)
                        .thenComparingInt(h -> h.xStart));

        for (int i = 0; i < detected.size(); i++) {
            detected.get(i).id = i;
        }

        return detected;
    }


    private int addHorizontalSegment(
            BufferedImage image,
            int threshold,
            int maxDistortion,
            Options options,
            List<HorizontalSegment> result,
            int nextId,
            int baseY,
            int startX,
            int endX,
            double scoreSum,
            int scoreCount) {

        if (startX < 0 || endX < startX) {
            return nextId;
        }

        int length =
                endX - startX + 1;

        if (length < options.minHorizontalLength) {
            return nextId;
        }

        /*
         * Ustalamy dokładniejsze Y:
         *
         * wybieramy ten Y w baseY +/- maxDistortion,
         * który ma największą liczbę ciemnych pikseli
         * na całej długości segmentu.
         */
        int bestY =
                refineHorizontalY(
                        image,
                        threshold,
                        startX,
                        endX,
                        baseY,
                        maxDistortion);

        double averageScore =
                scoreCount == 0
                        ? 0.0
                        : scoreSum / scoreCount;

        result.add(
                new HorizontalSegment(
                        nextId,
                        bestY,
                        startX,
                        endX,
                        averageScore));

        return nextId + 1;
    }


    /**
     * Określa, czy w kolumnie X, w pobliżu baseY,
     * znajduje się fragment poziomej linii.
     *
     * maxDistortion pozwala wybrać najlepsze lokalne Y.
     */
    private PointScore horizontalPointScore(
            BufferedImage image,
            int x,
            int baseY,
            int threshold,
            int maxDistortion,
            Options options) {

        PointScore best =
                PointScore.NONE;

        for (int candidateY = baseY - maxDistortion;
             candidateY <= baseY + maxDistortion;
             candidateY++) {

            PointScore score =
                    exactHorizontalPointScore(
                            image,
                            x,
                            candidateY,
                            threshold,
                            options);

            if (score.score > best.score) {
                best = score;
            }
        }

        return best;
    }


    /**
     * Ocenia profil pionowy wokół konkretnego Y.
     *
     * Oczekujemy:
     *
     *    jasne
     *    jasne
     *    CIEMNE
     *    CIEMNE
     *    jasne
     *    jasne
     *
     * czyli większej koncentracji ciemnych pikseli w centrum
     * niż dalej od linii.
     */
    private PointScore exactHorizontalPointScore(
            BufferedImage image,
            int x,
            int y,
            int threshold,
            Options options) {

        int lineRadius =
                (options.maxLineThickness - 1) / 2;

        int centerStart =
                y - lineRadius;

        int centerEnd =
                y + lineRadius;

        int centerSize =
                centerEnd - centerStart + 1;

        int centerDark = 0;

        for (int yy = centerStart;
             yy <= centerEnd;
             yy++) {

            if (pixelIsDark(
                    image,
                    x,
                    yy,
                    threshold)) {

                centerDark++;
            }
        }

        int upperDark = 0;
        int lowerDark = 0;

        for (int d = 1;
             d <= options.backgroundProbeSize;
             d++) {

            if (pixelIsDark(
                    image,
                    x,
                    centerStart - d,
                    threshold)) {

                upperDark++;
            }

            if (pixelIsDark(
                    image,
                    x,
                    centerEnd + d,
                    threshold)) {

                lowerDark++;
            }
        }

        double centerDensity =
                centerDark
                        / (double) centerSize;

        double upperDensity =
                upperDark
                        / (double) options.backgroundProbeSize;

        double lowerDensity =
                lowerDark
                        / (double) options.backgroundProbeSize;

        double backgroundDensity =
                (upperDensity + lowerDensity) / 2.0;

        double score =
                centerDensity
                        - options.backgroundPenalty
                        * backgroundDensity;

        return new PointScore(
                score,
                centerDensity);
    }


    /**
     * Wybiera reprezentatywne Y poziomej linii.
     *
     * Spośród:
     *
     * baseY-maxDistortion ... baseY+maxDistortion
     *
     * wybieramy Y z największą liczbą ciemnych pikseli
     * pomiędzy xStart i xEnd.
     */
    private int refineHorizontalY(
            BufferedImage image,
            int threshold,
            int xStart,
            int xEnd,
            int baseY,
            int maxDistortion) {

        int bestY =
                clamp(
                        baseY,
                        0,
                        image.getHeight() - 1);

        int bestCount = -1;

        for (int y = baseY - maxDistortion;
             y <= baseY + maxDistortion;
             y++) {

            int darkCount = 0;

            for (int x = xStart;
                 x <= xEnd;
                 x++) {

                if (pixelIsDark(
                        image,
                        x,
                        y,
                        threshold)) {

                    darkCount++;
                }
            }

            if (darkCount > bestCount) {

                bestCount = darkCount;
                bestY = y;
            }
        }

        return clamp(
                bestY,
                0,
                image.getHeight() - 1);
    }


    // ========================================================================
    // VERTICAL DETECTION
    // ========================================================================

    /**
     * Pionowe linie nie są wyszukiwane globalnie.
     *
     * Dla każdej linii poziomej:
     *
     * - przechodzimy wzdłuż jej X,
     * - próbujemy znaleźć start pionu w dół,
     * - próbujemy znaleźć start pionu w górę,
     * - jeśli istnieje, śledzimy pionową linię.
     */
    private List<VerticalSegment> findVerticalSegments(
            BufferedImage image,
            int threshold,
            int maxDistortion,
            List<HorizontalSegment> horizontal,
            Options options) {

        if (horizontal.isEmpty()) {
            return Collections.emptyList();
        }

        List<VerticalSegment> detected =
                new ArrayList<>();

        int nextId = 0;

        for (HorizontalSegment source : horizontal) {

            /*
             * Rozszerzamy zakres także poza formalny początek/koniec
             * poziomej linii.
             *
             * Pozwala to obsłużyć:
             *
             * -----------╮
             *            |
             */
            int fromX =
                    Math.max(
                            0,
                            source.xStart
                                    - options.connectionTolerance);

            int toX =
                    Math.min(
                            image.getWidth() - 1,
                            source.xEnd
                                    + options.connectionTolerance);

            for (int baseX = fromX;
                 baseX <= toX;
                 baseX++) {

                /*
                 * W dół.
                 */
                VerticalSegment down =
                        traceVertical(
                                image,
                                threshold,
                                maxDistortion,
                                source,
                                baseX,
                                +1,
                                options);

                if (down != null) {
                    down.id = nextId++;
                    detected.add(down);
                }

                /*
                 * W górę.
                 */
                VerticalSegment up =
                        traceVertical(
                                image,
                                threshold,
                                maxDistortion,
                                source,
                                baseX,
                                -1,
                                options);

                if (up != null) {
                    up.id = nextId++;
                    detected.add(up);
                }
            }
        }

        /*
         * Jedna fizyczna kreska może zostać znaleziona:
         *
         * - dla kilku sąsiednich X,
         * - od góry w dół,
         * - od dołu w górę,
         * - od kilku przecinanych linii poziomych.
         */
        detected =
                mergeVerticalSegments(
                        detected,
                        options,
                        maxDistortion);

        /*
         * Po scaleniu doprecyzowujemy X na podstawie całego
         * pionowego segmentu.
         */
        for (VerticalSegment segment : detected) {

            segment.x =
                    refineVerticalX(
                            image,
                            threshold,
                            segment.yStart,
                            segment.yEnd,
                            segment.x,
                            maxDistortion);
        }

        detected.sort(
                Comparator
                        .comparingInt((VerticalSegment v) -> v.x)
                        .thenComparingInt(v -> v.yStart));

        for (int i = 0; i < detected.size(); i++) {
            detected.get(i).id = i;
        }

        return detected;
    }


    /**
     * Znajduje początek pionowej linii w otoczeniu punktu
     * na linii poziomej, a następnie śledzi ją w zadanym kierunku.
     *
     * direction:
     *
     * +1 = w dół
     * -1 = w górę
     */
    private VerticalSegment traceVertical(
            BufferedImage image,
            int threshold,
            int maxDistortion,
            HorizontalSegment source,
            int baseX,
            int direction,
            Options options) {

        VerticalStart start =
                findVerticalStart(
                        image,
                        threshold,
                        maxDistortion,
                        source,
                        baseX,
                        direction,
                        options);

        if (start == null) {
            return null;
        }

        int firstCandidateY =
                start.y;

        int lastCandidateY =
                start.y;

        double scoreSum =
                start.score;

        int scoreCount = 1;

        int gap = 0;

        /*
         * Zaczynamy za znalezionym punktem startowym.
         */
        int y =
                start.y + direction;

        while (y >= 0
                && y < image.getHeight()) {

            PointScore score =
                    verticalPointScore(
                            image,
                            start.x,
                            y,
                            threshold,
                            maxDistortion,
                            options);

            if (isPointCandidate(score, options)) {

                lastCandidateY = y;

                gap = 0;

                scoreSum += score.score;
                scoreCount++;

            } else {

                gap++;

                if (gap > options.maxVerticalGap) {
                    break;
                }
            }

            y += direction;
        }

        int yStart =
                Math.min(
                        firstCandidateY,
                        lastCandidateY);

        int yEnd =
                Math.max(
                        firstCandidateY,
                        lastCandidateY);

        int length =
                yEnd - yStart + 1;

        if (length < options.minVerticalLength) {
            return null;
        }

        /*
         * start.x był tylko początkiem.
         *
         * Po znalezieniu całego pionowego segmentu wybieramy
         * najlepsze X na podstawie liczby ciemnych pikseli
         * na całej wysokości.
         */
        int bestX =
                refineVerticalX(
                        image,
                        threshold,
                        yStart,
                        yEnd,
                        start.x,
                        maxDistortion);

        double averageScore =
                scoreCount == 0
                        ? 0.0
                        : scoreSum / scoreCount;

        return new VerticalSegment(
                -1,
                bestX,
                yStart,
                yEnd,
                averageScore);
    }


    /**
     * Szuka początku pionowej linii w niewielkim otoczeniu punktu
     * leżącego na linii poziomej.
     *
     * Szukanie odbywa się:
     *
     * X: baseX +/- connectionTolerance
     *
     * Y:
     *   source.y ... source.y+connectionTolerance    dla DOWN
     *   source.y ... source.y-connectionTolerance    dla UP
     *
     * Pozwala to obsłużyć narożniki np.:
     *
     * ----------╮
     *           |
     *
     * gdzie pionowa kreska nie zaczyna się dokładnie
     * w tym samym X/Y.
     */
    private VerticalStart findVerticalStart(
            BufferedImage image,
            int threshold,
            int maxDistortion,
            HorizontalSegment source,
            int baseX,
            int direction,
            Options options) {

        VerticalStart best = null;

        for (int dx = -options.connectionTolerance;
             dx <= options.connectionTolerance;
             dx++) {

            int candidateX =
                    baseX + dx;

            if (candidateX < 0
                    || candidateX >= image.getWidth()) {

                continue;
            }

            for (int distance = 0;
                 distance <= options.connectionTolerance;
                 distance++) {

                int candidateY =
                        source.y
                                + direction * distance;

                if (candidateY < 0
                        || candidateY >= image.getHeight()) {

                    continue;
                }

                PointScore point =
                        verticalPointScore(
                                image,
                                candidateX,
                                candidateY,
                                threshold,
                                maxDistortion,
                                options);

                if (!isPointCandidate(
                        point,
                        options)) {

                    continue;
                }

                /*
                 * Preferujemy silny sygnał, ale przy podobnym sygnale
                 * lepszy jest punkt położony bliżej baseX/source.y.
                 */
                double distancePenalty;

                if (options.connectionTolerance == 0) {

                    distancePenalty = 0.0;

                } else {

                    distancePenalty =
                            (Math.abs(dx) + distance)
                                    / (double)
                                    (2 * options.connectionTolerance);
                }

                double rankScore =
                        point.score
                                - options.connectionDistancePenalty
                                * distancePenalty;

                if (best == null
                        || rankScore > best.rankScore) {

                    best =
                            new VerticalStart(
                                    candidateX,
                                    candidateY,
                                    point.score,
                                    rankScore);
                }
            }
        }

        return best;
    }


    /**
     * Analogiczny scoring do poziomego, tylko osie są zamienione.
     */
    private PointScore verticalPointScore(
            BufferedImage image,
            int baseX,
            int y,
            int threshold,
            int maxDistortion,
            Options options) {

        PointScore best =
                PointScore.NONE;

        for (int candidateX = baseX - maxDistortion;
             candidateX <= baseX + maxDistortion;
             candidateX++) {

            PointScore score =
                    exactVerticalPointScore(
                            image,
                            candidateX,
                            y,
                            threshold,
                            options);

            if (score.score > best.score) {
                best = score;
            }
        }

        return best;
    }


    private PointScore exactVerticalPointScore(
            BufferedImage image,
            int x,
            int y,
            int threshold,
            Options options) {

        int lineRadius =
                (options.maxLineThickness - 1) / 2;

        int centerStart =
                x - lineRadius;

        int centerEnd =
                x + lineRadius;

        int centerSize =
                centerEnd - centerStart + 1;

        int centerDark = 0;

        for (int xx = centerStart;
             xx <= centerEnd;
             xx++) {

            if (pixelIsDark(
                    image,
                    xx,
                    y,
                    threshold)) {

                centerDark++;
            }
        }

        int leftDark = 0;
        int rightDark = 0;

        for (int d = 1;
             d <= options.backgroundProbeSize;
             d++) {

            if (pixelIsDark(
                    image,
                    centerStart - d,
                    y,
                    threshold)) {

                leftDark++;
            }

            if (pixelIsDark(
                    image,
                    centerEnd + d,
                    y,
                    threshold)) {

                rightDark++;
            }
        }

        double centerDensity =
                centerDark
                        / (double) centerSize;

        double leftDensity =
                leftDark
                        / (double) options.backgroundProbeSize;

        double rightDensity =
                rightDark
                        / (double) options.backgroundProbeSize;

        double backgroundDensity =
                (leftDensity + rightDensity) / 2.0;

        double score =
                centerDensity
                        - options.backgroundPenalty
                        * backgroundDensity;

        return new PointScore(
                score,
                centerDensity);
    }


    /**
     * Odpowiednik refineHorizontalY().
     *
     * Wybiera X z największą liczbą ciemnych pikseli
     * na całej wysokości segmentu.
     */
    private int refineVerticalX(
            BufferedImage image,
            int threshold,
            int yStart,
            int yEnd,
            int baseX,
            int maxDistortion) {

        int bestX =
                clamp(
                        baseX,
                        0,
                        image.getWidth() - 1);

        int bestCount = -1;

        for (int x = baseX - maxDistortion;
             x <= baseX + maxDistortion;
             x++) {

            int darkCount = 0;

            for (int y = yStart;
                 y <= yEnd;
                 y++) {

                if (pixelIsDark(
                        image,
                        x,
                        y,
                        threshold)) {

                    darkCount++;
                }
            }

            if (darkCount > bestCount) {

                bestCount = darkCount;
                bestX = x;
            }
        }

        return clamp(
                bestX,
                0,
                image.getWidth() - 1);
    }


    // ========================================================================
    // MERGING HORIZONTAL SEGMENTS
    // ========================================================================

    private List<HorizontalSegment> mergeHorizontalSegments(
            List<HorizontalSegment> input,
            Options options,
            int maxDistortion) {

        if (input.isEmpty()) {
            return input;
        }

        input.sort(
                Comparator
                        .comparingInt(
                                (HorizontalSegment h) -> h.y)
                        .thenComparingInt(
                                h -> h.xStart));

        List<HorizontalSegment> result =
                new ArrayList<>();

        for (HorizontalSegment candidate : input) {

            HorizontalSegment bestMatch = null;

            for (int i = result.size() - 1;
                 i >= 0;
                 i--) {

                HorizontalSegment existing =
                        result.get(i);

                /*
                 * Starsze segmenty są już za daleko w Y.
                 */
                if (candidate.y - existing.y
                        > maxDistortion * 2 + 2) {

                    break;
                }

                boolean nearY =
                        Math.abs(
                                candidate.y - existing.y)
                                <= maxDistortion + 1;

                boolean compatibleX =
                        rangesOverlapOrNear(
                                candidate.xStart,
                                candidate.xEnd,
                                existing.xStart,
                                existing.xEnd,
                                options.maxHorizontalGap);

                if (nearY && compatibleX) {
                    bestMatch = existing;
                    break;
                }
            }

            if (bestMatch == null) {

                result.add(candidate);

            } else {

                mergeHorizontal(
                        bestMatch,
                        candidate);
            }
        }

        return result;
    }


    private void mergeHorizontal(
            HorizontalSegment target,
            HorizontalSegment other) {

        /*
         * Tymczasowo zachowujemy Y silniejszego detektora.
         * Po wszystkich merge i tak wykonywany jest refineHorizontalY().
         */
        if (other.score > target.score) {
            target.y = other.y;
        }

        target.xStart =
                Math.min(
                        target.xStart,
                        other.xStart);

        target.xEnd =
                Math.max(
                        target.xEnd,
                        other.xEnd);

        target.score =
                Math.max(
                        target.score,
                        other.score);
    }


    // ========================================================================
    // MERGING VERTICAL SEGMENTS
    // ========================================================================

    private List<VerticalSegment> mergeVerticalSegments(
            List<VerticalSegment> input,
            Options options,
            int maxDistortion) {

        if (input.isEmpty()) {
            return input;
        }

        input.sort(
                Comparator
                        .comparingInt(
                                (VerticalSegment v) -> v.x)
                        .thenComparingInt(
                                v -> v.yStart));

        List<VerticalSegment> result =
                new ArrayList<>();

        for (VerticalSegment candidate : input) {

            VerticalSegment bestMatch = null;

            for (int i = result.size() - 1;
                 i >= 0;
                 i--) {

                VerticalSegment existing =
                        result.get(i);

                if (candidate.x - existing.x
                        > maxDistortion * 2
                        + options.connectionTolerance
                        + 2) {

                    break;
                }

                boolean nearX =
                        Math.abs(
                                candidate.x - existing.x)
                                <= maxDistortion
                                + options.connectionTolerance;

                boolean compatibleY =
                        rangesOverlapOrNear(
                                candidate.yStart,
                                candidate.yEnd,
                                existing.yStart,
                                existing.yEnd,
                                options.maxVerticalGap
                                        + options.connectionTolerance);

                if (nearX && compatibleY) {

                    bestMatch = existing;
                    break;
                }
            }

            if (bestMatch == null) {

                result.add(candidate);

            } else {

                mergeVertical(
                        bestMatch,
                        candidate);
            }
        }

        return result;
    }


    private void mergeVertical(
            VerticalSegment target,
            VerticalSegment other) {

        if (other.score > target.score) {
            target.x = other.x;
        }

        target.yStart =
                Math.min(
                        target.yStart,
                        other.yStart);

        target.yEnd =
                Math.max(
                        target.yEnd,
                        other.yEnd);

        target.score =
                Math.max(
                        target.score,
                        other.score);
    }


    // ========================================================================
    // INTERSECTIONS
    // ========================================================================

    private List<Intersection> findIntersections(
            List<HorizontalSegment> horizontal,
            List<VerticalSegment> vertical,
            int tolerance) {

        List<Intersection> result =
                new ArrayList<>();

        for (HorizontalSegment h : horizontal) {

            for (VerticalSegment v : vertical) {

                /*
                 * Pozwalamy na niewielkie przesunięcie X.
                 */
                boolean xCompatible =
                        v.x >= h.xStart - tolerance
                                && v.x <= h.xEnd + tolerance;

                if (!xCompatible) {
                    continue;
                }

                /*
                 * Pion może zaczynać się trochę poniżej albo kończyć
                 * trochę powyżej poziomej kreski.
                 */
                boolean yCompatible =
                        h.y >= v.yStart - tolerance
                                && h.y <= v.yEnd + tolerance;

                if (!yCompatible) {
                    continue;
                }

                IntersectionType type;

                if (Math.abs(
                        h.y - v.yStart) <= tolerance) {

                    type =
                            IntersectionType.VERTICAL_START;

                } else if (Math.abs(
                        h.y - v.yEnd) <= tolerance) {

                    type =
                            IntersectionType.VERTICAL_END;

                } else {

                    type =
                            IntersectionType.CROSS;
                }

                int dx =
                        distanceToRange(
                                v.x,
                                h.xStart,
                                h.xEnd);

                int dy =
                        distanceToRange(
                                h.y,
                                v.yStart,
                                v.yEnd);

                double distance =
                        Math.sqrt(
                                dx * (double) dx
                                        + dy * (double) dy);

                double connectionScore;

                if (tolerance == 0) {

                    connectionScore =
                            distance == 0.0
                                    ? 1.0
                                    : 0.0;

                } else {

                    connectionScore =
                            Math.max(
                                    0.0,
                                    1.0
                                            - distance
                                            / tolerance);
                }

                result.add(
                        new Intersection(
                                h.id,
                                v.id,
                                type,
                                connectionScore));
            }
        }

        return result;
    }


    // ========================================================================
    // HORIZONTAL PAIR CANDIDATES
    // ========================================================================

    private List<HorizontalPairCandidate>
    buildHorizontalPairCandidates(
            List<HorizontalSegment> horizontal,
            List<VerticalSegment> vertical,
            List<Intersection> intersections,
            Options options) {

        List<HorizontalPairCandidate> result =
                new ArrayList<>();

        for (int i = 0;
             i < horizontal.size();
             i++) {

            HorizontalSegment top =
                    horizontal.get(i);

            for (int j = i + 1;
                 j < horizontal.size();
                 j++) {

                HorizontalSegment bottom =
                        horizontal.get(j);

                if (bottom.y <= top.y) {
                    continue;
                }

                int distance =
                        bottom.y - top.y;

                /*
                 * Jeżeli oczekiwana wysokość jest podana,
                 * odrzucamy dopiero wartości całkiem poza ustalonym
                 * zakresem 80%-120% itd.
                 */
                if (!isRowHeightAllowed(
                        distance,
                        options)) {

                    continue;
                }

                List<Integer> commonVerticalIds =
                        findCommonVerticals(
                                top.id,
                                bottom.id,
                                intersections);

                /*
                 * Para bez ani jednego wspólnego pionowego połączenia
                 * nie opisuje struktury tabeli.
                 */
                if (commonVerticalIds.isEmpty()) {
                    continue;
                }

                double connectionScore =
                        calculatePairConnectionScore(
                                top.id,
                                bottom.id,
                                commonVerticalIds,
                                intersections);

                double heightScore =
                        calculateHeightScore(
                                distance,
                                options);

                double finalScore =
                        normalizeWeightedScore(
                                connectionScore,
                                options.connectionScoreWeight,
                                heightScore,
                                options.rowHeightScoreWeight);

                result.add(
                        new HorizontalPairCandidate(
                                top.id,
                                bottom.id,
                                distance,
                                commonVerticalIds,
                                connectionScore,
                                heightScore,
                                finalScore));
            }
        }

        result.sort(
                Comparator
                        .comparingDouble(
                                (HorizontalPairCandidate p) ->
                                        p.score)
                        .reversed());

        return result;
    }


    private boolean isRowHeightAllowed(
            int actualDistance,
            Options options) {

        if (options.expectedRowHeight == null
                || options.expectedRowHeight <= 0) {

            return true;
        }

        double expected =
                options.expectedRowHeight;

        double min =
                expected
                        * options.minRowHeightFactor;

        double max =
                expected
                        * options.maxRowHeightFactor;

        return actualDistance >= min
                && actualDistance <= max;
    }


    private List<Integer> findCommonVerticals(
            int horizontalA,
            int horizontalB,
            List<Intersection> intersections) {

        Set<Integer> a =
                new HashSet<>();

        Set<Integer> b =
                new HashSet<>();

        for (Intersection intersection :
                intersections) {

            if (intersection.horizontalId
                    == horizontalA) {

                a.add(
                        intersection.verticalId);
            }

            if (intersection.horizontalId
                    == horizontalB) {

                b.add(
                        intersection.verticalId);
            }
        }

        a.retainAll(b);

        List<Integer> result =
                new ArrayList<>(a);

        Collections.sort(result);

        return result;
    }


    private double calculatePairConnectionScore(
            int topId,
            int bottomId,
            List<Integer> verticalIds,
            List<Intersection> intersections) {

        if (verticalIds.isEmpty()) {
            return 0.0;
        }

        double sum = 0.0;

        for (Integer verticalId :
                verticalIds) {

            double topScore = 0.0;
            double bottomScore = 0.0;

            for (Intersection intersection :
                    intersections) {

                if (intersection.verticalId
                        != verticalId) {

                    continue;
                }

                if (intersection.horizontalId
                        == topId) {

                    topScore =
                            Math.max(
                                    topScore,
                                    intersection.score);
                }

                if (intersection.horizontalId
                        == bottomId) {

                    bottomScore =
                            Math.max(
                                    bottomScore,
                                    intersection.score);
                }
            }

            /*
             * Pionowe połączenie jest tak dobre jak jego słabszy koniec.
             */
            sum +=
                    Math.min(
                            topScore,
                            bottomScore);
        }

        double average =
                sum / verticalIds.size();

        /*
         * Większa liczba pionowych połączeń zwiększa wiarygodność,
         * ale bonus jest ograniczony asymptotycznie.
         */
        double countBonus =
                1.0
                        - Math.exp(
                                -verticalIds.size());

        return average
                * countBonus;
    }


    private double calculateHeightScore(
            int actualDistance,
            Options options) {

        if (options.expectedRowHeight == null
                || options.expectedRowHeight <= 0) {

            /*
             * Jeśli nie mamy priora wysokości,
             * nie karzemy kandydata.
             */
            return 1.0;
        }

        double expected =
                options.expectedRowHeight;

        double min =
                expected
                        * options.minRowHeightFactor;

        double max =
                expected
                        * options.maxRowHeightFactor;

        if (actualDistance < min
                || actualDistance > max) {

            return 0.0;
        }

        if (actualDistance == expected) {
            return 1.0;
        }

        if (actualDistance < expected) {

            double range =
                    expected - min;

            if (range <= 0.0) {
                return 0.0;
            }

            return 1.0
                    - (expected - actualDistance)
                    / range;

        } else {

            double range =
                    max - expected;

            if (range <= 0.0) {
                return 0.0;
            }

            return 1.0
                    - (actualDistance - expected)
                    / range;
        }
    }


    private double normalizeWeightedScore(
            double score1,
            double weight1,
            double score2,
            double weight2) {

        double weightSum =
                weight1 + weight2;

        if (weightSum <= 0.0) {
            return 0.0;
        }

        return (
                score1 * weight1
                        + score2 * weight2
        ) / weightSum;
    }


    // ========================================================================
    // PIXEL ACCESS
    // ========================================================================

    /**
     * Wszystkie piksele poza obrazem są semantycznie białe.
     *
     * Dzięki temu scoring działa również dla:
     *
     * y = 0,
     * y = height - 1,
     * x = 0,
     * x = width - 1.
     */
    private boolean pixelIsDark(
            BufferedImage image,
            int x,
            int y,
            int threshold) {

        if (x < 0
                || y < 0
                || x >= image.getWidth()
                || y >= image.getHeight()) {

            return false;
        }

        return isDark(
                image.getRGB(x, y),
                threshold);
    }


    // ========================================================================
    // COMMON HELPERS
    // ========================================================================

    private boolean isPointCandidate(
            PointScore score,
            Options options) {

        return score.score
                >= options.minPointScore
                && score.centerDensity
                >= options.minCenterDensity;
    }


    private static boolean rangesOverlapOrNear(
            int a1,
            int a2,
            int b1,
            int b2,
            int tolerance) {

        return a1 <= b2 + tolerance
                && b1 <= a2 + tolerance;
    }


    private static int distanceToRange(
            int value,
            int min,
            int max) {

        if (value < min) {
            return min - value;
        }

        if (value > max) {
            return value - max;
        }

        return 0;
    }


    private static int clamp(
            int value,
            int min,
            int max) {

        return Math.max(
                min,
                Math.min(
                        max,
                        value));
    }


    private void validateOptions(
            Options options) {

        if (options.maxHorizontalGap < 0) {
            throw new IllegalArgumentException(
                    "maxHorizontalGap must be >= 0");
        }

        if (options.maxVerticalGap < 0) {
            throw new IllegalArgumentException(
                    "maxVerticalGap must be >= 0");
        }

        if (options.minHorizontalLength < 1) {
            throw new IllegalArgumentException(
                    "minHorizontalLength must be >= 1");
        }

        if (options.minVerticalLength < 1) {
            throw new IllegalArgumentException(
                    "minVerticalLength must be >= 1");
        }

        if (options.maxLineThickness < 1) {
            throw new IllegalArgumentException(
                    "maxLineThickness must be >= 1");
        }

        if (options.backgroundProbeSize < 1) {
            throw new IllegalArgumentException(
                    "backgroundProbeSize must be >= 1");
        }

        if (options.connectionTolerance < 0) {
            throw new IllegalArgumentException(
                    "connectionTolerance must be >= 0");
        }

        if (options.minCenterDensity < 0.0
                || options.minCenterDensity > 1.0) {

            throw new IllegalArgumentException(
                    "minCenterDensity must be in range 0..1");
        }

        if (options.backgroundPenalty < 0.0) {
            throw new IllegalArgumentException(
                    "backgroundPenalty must be >= 0");
        }

        if (options.connectionDistancePenalty < 0.0) {
            throw new IllegalArgumentException(
                    "connectionDistancePenalty must be >= 0");
        }

        if (options.minRowHeightFactor <= 0.0) {
            throw new IllegalArgumentException(
                    "minRowHeightFactor must be > 0");
        }

        if (options.maxRowHeightFactor
                < options.minRowHeightFactor) {

            throw new IllegalArgumentException(
                    "maxRowHeightFactor must be >= minRowHeightFactor");
        }

        if (options.connectionScoreWeight < 0.0
                || options.rowHeightScoreWeight < 0.0) {

            throw new IllegalArgumentException(
                    "score weights must be >= 0");
        }
    }


    // ========================================================================
    // OPTIONS
    // ========================================================================

    public static class Options {

        /**
         * Maksymalna przerwa wewnątrz poziomej kreski.
         *
         * Dla mocno uszkodzonych skanów może być relatywnie duża.
         */
        public int maxHorizontalGap = 25;

        /**
         * Maksymalna przerwa podczas śledzenia pionowej kreski.
         */
        public int maxVerticalGap = 10;

        /**
         * Minimalna długość poziomego segmentu.
         */
        public int minHorizontalLength = 30;

        /**
         * Minimalna długość pionowego segmentu.
         */
        public int minVerticalLength = 10;

        /**
         * Maksymalna oczekiwana fizyczna grubość kreski.
         *
         * To NIE jest maxDistortion.
         */
        public int maxLineThickness = 3;

        /**
         * Ile pikseli poza centralną częścią kreski analizować
         * jako oczekiwane jasne tło.
         */
        public int backgroundProbeSize = 3;

        /**
         * Kara za ciemne piksele po obu stronach kreski.
         */
        public double backgroundPenalty = 0.75;

        /**
         * Minimalna gęstość ciemnych pikseli w centrum profilu.
         *
         * Jest celowo niska ze względu na uszkodzone skany.
         */
        public double minCenterDensity = 0.25;

        /**
         * Minimalna różnica:
         *
         * centerDensity - backgroundPenalty * backgroundDensity
         */
        public double minPointScore = 0.15;

        /**
         * Tolerancja połączenia linii H/V.
         *
         * Obsługuje np. zaokrąglone narożniki o promieniu 2-3 px.
         */
        public int connectionTolerance = 3;

        /**
         * Przy wybieraniu najlepszego miejsca startu pionowej kreski
         * lekko preferujemy punkt bliższy geometrycznie do baseX/sourceY.
         */
        public double connectionDistancePenalty = 0.15;

        /**
         * Opcjonalna spodziewana odległość pomiędzy liniami poziomymi.
         *
         * null oznacza brak takiej informacji.
         */
        public Integer expectedRowHeight = null;

        /**
         * Przy expectedRowHeight=50 i wartościach 0.8 / 1.2:
         *
         * zakres dopuszczalny = 40..60.
         */
        public double minRowHeightFactor = 0.8;

        public double maxRowHeightFactor = 1.2;

        /**
         * W istnieniu pionowych połączeń pokładamy większe zaufanie
         * niż w sugerowanej wysokości wiersza.
         */
        public double connectionScoreWeight = 0.8;

        public double rowHeightScoreWeight = 0.2;
    }


    // ========================================================================
    // RESULT
    // ========================================================================

    public static class TableStructure {

        public final List<HorizontalSegment> horizontalLines;

        public final List<VerticalSegment> verticalLines;

        public final List<Intersection> intersections;

        /**
         * Potencjalne pary:
         *
         * Horizontal Top
         *       |
         *       | pionowe połączenia
         *       |
         * Horizontal Bottom
         *
         * Posortowane malejąco według score.
         */
        public final List<HorizontalPairCandidate> rowCandidates;


        public TableStructure(
                List<HorizontalSegment> horizontalLines,
                List<VerticalSegment> verticalLines,
                List<Intersection> intersections,
                List<HorizontalPairCandidate> rowCandidates) {

            this.horizontalLines =
                    Collections.unmodifiableList(
                            new ArrayList<>(
                                    horizontalLines));

            this.verticalLines =
                    Collections.unmodifiableList(
                            new ArrayList<>(
                                    verticalLines));

            this.intersections =
                    Collections.unmodifiableList(
                            new ArrayList<>(
                                    intersections));

            this.rowCandidates =
                    Collections.unmodifiableList(
                            new ArrayList<>(
                                    rowCandidates));
        }
    }


    public static class HorizontalSegment {

        public int id;

        public int y;

        public int xStart;

        public int xEnd;

        public double score;


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


        public int length() {
            return xEnd - xStart + 1;
        }


        @Override
        public String toString() {

            return "HorizontalSegment{" +
                    "id=" + id +
                    ", y=" + y +
                    ", xStart=" + xStart +
                    ", xEnd=" + xEnd +
                    ", length=" + length() +
                    ", score=" + score +
                    '}';
        }
    }


    public static class VerticalSegment {

        public int id;

        public int x;

        public int yStart;

        public int yEnd;

        public double score;


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


        public int length() {
            return yEnd - yStart + 1;
        }


        @Override
        public String toString() {

            return "VerticalSegment{" +
                    "id=" + id +
                    ", x=" + x +
                    ", yStart=" + yStart +
                    ", yEnd=" + yEnd +
                    ", length=" + length() +
                    ", score=" + score +
                    '}';
        }
    }


    public enum IntersectionType {

        /**
         * Linia pozioma znajduje się blisko początku pionowej.
         */
        VERTICAL_START,

        /**
         * Linia pozioma znajduje się blisko końca pionowej.
         */
        VERTICAL_END,

        /**
         * Linia pionowa przechodzi przez poziomą.
         */
        CROSS
    }


    public static class Intersection {

        public final int horizontalId;

        public final int verticalId;

        public final IntersectionType type;

        public final double score;


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


        @Override
        public String toString() {

            return "Intersection{" +
                    "horizontalId=" + horizontalId +
                    ", verticalId=" + verticalId +
                    ", type=" + type +
                    ", score=" + score +
                    '}';
        }
    }


    public static class HorizontalPairCandidate {

        public final int topHorizontalId;

        public final int bottomHorizontalId;

        public final int height;

        /**
         * ID pionowych linii przecinających/łączących obie poziome.
         */
        public final List<Integer> verticalIds;

        public final double connectionScore;

        public final double heightScore;

        public final double score;


        public HorizontalPairCandidate(
                int topHorizontalId,
                int bottomHorizontalId,
                int height,
                List<Integer> verticalIds,
                double connectionScore,
                double heightScore,
                double score) {

            this.topHorizontalId =
                    topHorizontalId;

            this.bottomHorizontalId =
                    bottomHorizontalId;

            this.height =
                    height;

            this.verticalIds =
                    Collections.unmodifiableList(
                            new ArrayList<>(
                                    verticalIds));

            this.connectionScore =
                    connectionScore;

            this.heightScore =
                    heightScore;

            this.score =
                    score;
        }


        @Override
        public String toString() {

            return "HorizontalPairCandidate{" +
                    "topHorizontalId=" + topHorizontalId +
                    ", bottomHorizontalId=" + bottomHorizontalId +
                    ", height=" + height +
                    ", verticalIds=" + verticalIds +
                    ", connectionScore=" + connectionScore +
                    ", heightScore=" + heightScore +
                    ", score=" + score +
                    '}';
        }
    }


    // ========================================================================
    // INTERNAL DATA
    // ========================================================================

    private static class PointScore {

        static final PointScore NONE =
                new PointScore(
                        Double.NEGATIVE_INFINITY,
                        0.0);

        final double score;

        final double centerDensity;


        PointScore(
                double score,
                double centerDensity) {

            this.score =
                    score;

            this.centerDensity =
                    centerDensity;
        }
    }


    private static class VerticalStart {

        final int x;

        final int y;

        /**
         * Faktyczny score pionowego sygnału.
         */
        final double score;

        /**
         * Score używany tylko przy wyborze najlepszego startu.
         * Uwzględnia karę za odległość od baseX/sourceY.
         */
        final double rankScore;


        VerticalStart(
                int x,
                int y,
                double score,
                double rankScore) {

            this.x = x;
            this.y = y;
            this.score = score;
            this.rankScore = rankScore;
        }
    }
}
