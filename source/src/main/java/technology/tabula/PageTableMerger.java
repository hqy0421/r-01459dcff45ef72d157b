package technology.tabula;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Opt-in post-processing for lattice tables that continue across a page break.
 *
 * <p>Supplier order PDFs often repeat the column-header row on every page. Sometimes a single
 * order is also split by the page break: the last row of one page continues in the first data row
 * of the next page, without repeating the order number in the first column.
 *
 * <p>Two tables are joined only when <em>all</em> of the following hold:
 * <ul>
 *   <li>they live on adjacent physical pages (page numbers differ by exactly one),</li>
 *   <li>they were extracted from the same {@code --area} selection,</li>
 *   <li>their normalized header rows are identical,</li>
 *   <li>their column boundaries agree,</li>
 *   <li>the first table ends close to the bottom of its page and the second starts close to the
 *       top of its page, and</li>
 *   <li>each table has exactly one compatible counterpart on the other page (uniqueness).</li>
 * </ul>
 * When joined, only the continuation page's header row is dropped. If the first data row after
 * that header has no order number while the previous page's last row does, the two rows are joined
 * column by column and embedded line breaks are kept. Any table that does not meet the conditions
 * is returned untouched, so the default export is unaffected.
 *
 * <p>Merged tables keep the geometry, page number and page height of their first fragment; the
 * continuation fragments contribute rows only.
 */
public class PageTableMerger {

    /** Fraction of a page's height that counts as "close to the top/bottom edge". */
    public static final float DEFAULT_EDGE_TOLERANCE_FRACTION = 0.10f;

    /** Maximum allowed difference, in points, between two matching column boundaries. */
    public static final float DEFAULT_COLUMN_TOLERANCE = 2.0f;

    /** {@link Table#getExtractionMethod()} of lattice-mode tables. */
    private static final String LATTICE = "lattice";

    private final float edgeToleranceFraction;
    private final float columnTolerance;

    public PageTableMerger() {
        this(DEFAULT_EDGE_TOLERANCE_FRACTION, DEFAULT_COLUMN_TOLERANCE);
    }

    public PageTableMerger(float edgeToleranceFraction, float columnTolerance) {
        if (edgeToleranceFraction < 0 || edgeToleranceFraction > 1) {
            throw new IllegalArgumentException("edgeToleranceFraction must be between 0 and 1");
        }
        if (columnTolerance < 0) {
            throw new IllegalArgumentException("columnTolerance can't be negative");
        }
        this.edgeToleranceFraction = edgeToleranceFraction;
        this.columnTolerance = columnTolerance;
    }

    public static List<Table> merge(List<Table> tables) {
        return new PageTableMerger().mergeTables(tables);
    }

    /**
     * Returns the tables with cross-page continuations joined, preserving document order.
     */
    public List<Table> mergeTables(List<Table> tables) {
        List<Table> rv = new ArrayList<>(tables);
        int n = rv.size();
        if (n < 2) {
            return rv;
        }

        // Stable sort by starting page; extraction order is kept within a page.
        Collections.sort(rv, (t1, t2) -> Integer.compare(t1.getPageNumber(), t2.getPageNumber()));

        // Per-table state of the last fragment (a table may span more than two pages).
        int[] lastPage = new int[n];
        float[] lastBottom = new float[n];
        float[] lastPageHeight = new float[n];
        boolean[] absorbed = new boolean[n];
        for (int i = 0; i < n; i++) {
            Table t = rv.get(i);
            lastPage[i] = t.getPageNumber();
            lastBottom[i] = t.getBottom();
            lastPageHeight[i] = t.getPageHeight();
        }

        List<Integer> distinctPages = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int page = rv.get(i).getPageNumber();
            if (distinctPages.isEmpty() || distinctPages.get(distinctPages.size() - 1) != page) {
                distinctPages.add(page);
            }
        }

        for (int p = 0; p + 1 < distinctPages.size(); p++) {
            int firstPage = distinctPages.get(p);
            int secondPage = distinctPages.get(p + 1);
            if (secondPage != firstPage + 1) {
                // Skipped page(s): never join across a gap.
                continue;
            }

            List<Integer> prevIdxs = new ArrayList<>();
            List<Integer> nextIdxs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (absorbed[i]) {
                    continue;
                }
                if (lastPage[i] == firstPage) {
                    prevIdxs.add(i);
                } else if (rv.get(i).getPageNumber() == secondPage) {
                    nextIdxs.add(i);
                }
            }

            if (prevIdxs.isEmpty() || nextIdxs.isEmpty()) {
                continue;
            }

            boolean[][] compatible = new boolean[prevIdxs.size()][nextIdxs.size()];
            int[] prevCandidateCount = new int[prevIdxs.size()];
            int[] nextCandidateCount = new int[nextIdxs.size()];
            for (int a = 0; a < prevIdxs.size(); a++) {
                for (int b = 0; b < nextIdxs.size(); b++) {
                    Table prev = rv.get(prevIdxs.get(a));
                    Table next = rv.get(nextIdxs.get(b));
                    if (canContinue(prev, lastBottom[prevIdxs.get(a)], lastPageHeight[prevIdxs.get(a)], next)) {
                        compatible[a][b] = true;
                        prevCandidateCount[a]++;
                        nextCandidateCount[b]++;
                    }
                }
            }

            for (int a = 0; a < prevIdxs.size(); a++) {
                for (int b = 0; b < nextIdxs.size(); b++) {
                    if (!compatible[a][b] || prevCandidateCount[a] != 1 || nextCandidateCount[b] != 1) {
                        // Missing or ambiguous counterpart: keep the tables independent.
                        continue;
                    }
                    int prevIdx = prevIdxs.get(a);
                    int nextIdx = nextIdxs.get(b);
                    Table next = rv.get(nextIdx);
                    rv.set(prevIdx, mergePair(rv.get(prevIdx), next));
                    lastPage[prevIdx] = secondPage;
                    lastBottom[prevIdx] = next.getBottom();
                    lastPageHeight[prevIdx] = next.getPageHeight();
                    absorbed[nextIdx] = true;
                }
            }
        }

        List<Table> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (!absorbed[i]) {
                result.add(rv.get(i));
            }
        }
        return result;
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - //

    private boolean canContinue(Table prev, float prevBottom, float prevPageHeight, Table next) {
        if (!LATTICE.equals(prev.getExtractionMethod()) || !LATTICE.equals(next.getExtractionMethod())) {
            // Only ruled (lattice) tables have the stable columns and repeated header this relies on.
            return false;
        }
        if (prev.getSelectionAreaIndex() != next.getSelectionAreaIndex()) {
            return false;
        }
        if (prev.getRowCount() < 2 || next.getRowCount() < 2) {
            // Both fragments must have a header plus at least one data row; a lone trailing
            // header shouldn't swallow the next page.
            return false;
        }
        if (prev.getColCount() < 1 || prev.getColCount() != next.getColCount()) {
            return false;
        }
        if (!headersMatch(prev, next)) {
            return false;
        }
        if (!columnBoundariesMatch(prev, next)) {
            return false;
        }
        return nearPageBreak(prevBottom, prevPageHeight, next);
    }

    private boolean nearPageBreak(float prevBottom, float prevPageHeight, Table next) {
        float nextPageHeight = next.getPageHeight();
        if (Float.isNaN(prevPageHeight) || Float.isNaN(nextPageHeight) ||
                prevPageHeight <= 0 || nextPageHeight <= 0) {
            // Without page geometry we can't verify the page-break layout.
            return false;
        }
        float prevTolerance = prevPageHeight * edgeToleranceFraction;
        float nextTolerance = nextPageHeight * edgeToleranceFraction;
        return prevBottom >= prevPageHeight - prevTolerance && prevBottom <= prevPageHeight + prevTolerance
                && next.getTop() >= -nextTolerance && next.getTop() <= nextTolerance;
    }

    private boolean headersMatch(Table prev, Table next) {
        List<RectangularTextContainer> prevHeader = prev.getRows().get(0);
        List<RectangularTextContainer> nextHeader = next.getRows().get(0);
        if (prevHeader.size() != nextHeader.size()) {
            return false;
        }
        boolean hasContent = false;
        for (int i = 0; i < prevHeader.size(); i++) {
            String prevText = normalizeHeader(prevHeader.get(i).getText());
            String nextText = normalizeHeader(nextHeader.get(i).getText());
            if (!prevText.equals(nextText)) {
                return false;
            }
            hasContent |= !prevText.isEmpty();
        }
        return hasContent;
    }

    /**
     * Case-insensitive, whitespace-free, Unicode-normalized header text. Removing all whitespace
     * makes headers that wrap differently on the two pages compare equal.
     */
    private static String normalizeHeader(String text) {
        if (text == null) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        normalized = normalized.replaceAll("\\s+", "");
        return normalized.toLowerCase(Locale.ROOT);
    }

    private boolean columnBoundariesMatch(Table prev, Table next) {
        List<Float> prevBoundaries = columnBoundaries(prev);
        List<Float> nextBoundaries = columnBoundaries(next);
        if (prevBoundaries.size() != nextBoundaries.size() || prevBoundaries.size() < 2) {
            return false;
        }
        for (int i = 0; i < prevBoundaries.size(); i++) {
            if (Math.abs(prevBoundaries.get(i) - nextBoundaries.get(i)) > columnTolerance) {
                return false;
            }
        }
        return true;
    }

    private List<Float> columnBoundaries(Table table) {
        List<Float> xs = new ArrayList<>();
        boolean fromRulings = false;
        if (table instanceof TableWithRulingLines) {
            List<Ruling> rulings = ((TableWithRulingLines) table).verticalRulings;
            if (rulings != null) {
                for (Ruling ruling : rulings) {
                    if (ruling.vertical()) {
                        xs.add(ruling.getLeft());
                        fromRulings = true;
                    }
                }
            }
        }
        if (!fromRulings) {
            for (List<RectangularTextContainer> row : table.getRows()) {
                for (RectangularTextContainer cell : row) {
                    if (cell != TextChunk.EMPTY && cell.getWidth() > 0) {
                        xs.add(cell.getLeft());
                    }
                }
            }
        }
        xs.add(table.getLeft());
        xs.add(table.getRight());

        Collections.sort(xs);
        List<Float> distinct = new ArrayList<>(xs.size());
        for (Float x : xs) {
            if (distinct.isEmpty() || Math.abs(x - distinct.get(distinct.size() - 1)) > 0.01f) {
                distinct.add(x);
            }
        }
        return distinct;
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - //

    private Table mergePair(Table prev, Table next) {
        Table merged = prev.copy();
        int colCount = prev.getColCount();
        List<List<RectangularTextContainer>> prevRows = prev.getRows();
        List<List<RectangularTextContainer>> nextRows = next.getRows();

        int firstNextDataRow = 1; // row 0 of the continuation page is its repeated header
        if (shouldContinueLastRow(prevRows, nextRows)) {
            joinRow(merged, prevRows.get(prev.getRowCount() - 1), nextRows.get(1));
            firstNextDataRow = 2;
        }

        int mergedRow = prev.getRowCount();
        for (int r = firstNextDataRow; r < next.getRowCount(); r++) {
            List<RectangularTextContainer> nextRow = nextRows.get(r);
            boolean addedAny = false;
            for (int c = 0; c < colCount && c < nextRow.size(); c++) {
                RectangularTextContainer cell = nextRow.get(c);
                if (cell == TextChunk.EMPTY) {
                    continue;
                }
                merged.add(cell, mergedRow, c);
                addedAny = true;
            }
            if (!addedAny) {
                // Keep the row slot even if every cell was blank.
                merged.add(new TextChunk(0, 0, 0, 0), mergedRow, 0);
            }
            mergedRow++;
        }

        // A merged table describes the table as it started; the appended rows keep their text but
        // the geometry of continuation pages isn't projected onto the first page's rectangle.
        merged.setRect(prev);
        return merged;
    }

    /**
     * True when the continuation page's first data row is the wrapped remainder of the previous
     * page's last order: the previous row starts with an order number, while the continuation row
     * has no number at all in its first column and does carry text in another column.
     */
    private boolean shouldContinueLastRow(List<List<RectangularTextContainer>> prevRows,
                                          List<List<RectangularTextContainer>> nextRows) {
        if (prevRows.isEmpty() || nextRows.size() < 2) {
            return false;
        }
        List<RectangularTextContainer> lastPrevRow = prevRows.get(prevRows.size() - 1);
        List<RectangularTextContainer> continuation = nextRows.get(1);
        if (lastPrevRow.isEmpty() || continuation.isEmpty()) {
            return false;
        }
        if (!isOrderNumber(lastPrevRow.get(0).getText().trim())) {
            return false;
        }
        // "No order number" means the first column is blank. A non-blank non-numeric value is
        // treated as a different identifier and doesn't trigger a column-wise join.
        if (!continuation.get(0).getText().trim().isEmpty()) {
            return false;
        }
        for (int c = 1; c < continuation.size(); c++) {
            if (!continuation.get(c).getText().trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * A first-column value that looks like an order number: no internal whitespace and at least one
     * digit. Deliberately permissive about prefixes and separators ("A001", "PO-2024-001", "#17").
     */
    private static boolean isOrderNumber(String text) {
        if (text.isEmpty() || text.length() > 64) {
            return false;
        }
        boolean hasDigit = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                return false;
            }
            if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        return hasDigit;
    }

    private void joinRow(Table merged, List<RectangularTextContainer> prevRow,
                         List<RectangularTextContainer> continuationRow) {
        int lastRow = merged.getRowCount() - 1;
        int columns = Math.min(prevRow.size(), continuationRow.size());
        for (int c = 0; c < columns; c++) {
            RectangularTextContainer prevCell = prevRow.get(c);
            RectangularTextContainer continuationCell = continuationRow.get(c);
            String continuationText = continuationCell.getText().trim();
            if (continuationText.isEmpty()) {
                continue;
            }
            String prevText = prevCell.getText().trim();
            String joinedText = prevText.isEmpty() ? continuationText : prevText + "\r" + continuationText;

            RectangularTextContainer geometry = prevCell != TextChunk.EMPTY && prevCell.getWidth() > 0
                    ? prevCell
                    : continuationCell;
            // Replaces the previous page's cell without touching the original table.
            merged.cells.put(new CellPosition(lastRow, c),
                    joinedCell(geometry, joinedText));
        }
        merged.invalidateRows();
    }

    /**
     * Builds a plain {@link Cell} holding a single text run. The run is supplied verbatim so any
     * embedded line breaks from either page are kept; a cell built from real text positions can't
     * be used here because the two fragments live in different page coordinate systems. Using a
     * plain Cell (rather than a subclass) keeps the JSON writer's Cell serializer working.
     */
    private static Cell joinedCell(RectangularTextContainer geometry, String text) {
        Cell cell = new Cell(geometry.getTop(), geometry.getLeft(),
                (float) geometry.getWidth(), (float) geometry.getHeight());
        TextElement element = new TextElement(geometry.getTop(), geometry.getLeft(),
                (float) geometry.getWidth(), (float) geometry.getHeight(),
                null, 10f, text, 2f);
        cell.setTextElements(Collections.singletonList(new TextChunk(element)));
        return cell;
    }
}
