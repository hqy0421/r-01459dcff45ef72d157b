package technology.tabula;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import technology.tabula.extractors.ExtractionAlgorithm;

@SuppressWarnings("serial")
public class Table extends Rectangle {

	public static final Table empty() { return new Table(""); }

	private Table(String extractionMethod) {
		this.extractionMethod = extractionMethod;
	}

	public Table(ExtractionAlgorithm extractionAlgorithm) {
		this(extractionAlgorithm.toString());
	}

	private final String extractionMethod;

	private int rowCount = 0;
	private int colCount = 0;
	private int pageNumber = 0;
	private int selectionAreaIndex = 0;
	private float pageHeight = java.lang.Float.NaN;

	/* visible for testing */ final TreeMap<CellPosition, RectangularTextContainer> cells = new TreeMap<>();

	public Table(Table other) {
		this(other.extractionMethod);
		this.setRect(other);
		this.rowCount = other.rowCount;
		this.colCount = other.colCount;
		this.pageNumber = other.pageNumber;
		this.selectionAreaIndex = other.selectionAreaIndex;
		this.pageHeight = other.pageHeight;
		this.cells.putAll(other.cells);
	}

	/**
	 * Creates a shallow copy (cells are shared, the grid is not) of this table. Subclasses that
	 * carry extra geometry, such as {@link TableWithRulingLines}, override this to preserve it.
	 */
	public Table copy() {
		return new Table(this);
	}

	public int getRowCount() { return rowCount; }
	public int getColCount() { return colCount; }
	public int getPageNumber() { return pageNumber; }
	public void setPageNumber(int pageNumber) {	this.pageNumber = pageNumber; }

	/**
	 * Index of the {@code --area} selection this table was extracted from. Tables coming from
	 * different selection areas on the same page are never merged across pages.
	 */
	public int getSelectionAreaIndex() { return selectionAreaIndex; }
	public void setSelectionAreaIndex(int selectionAreaIndex) { this.selectionAreaIndex = selectionAreaIndex; }

	/**
	 * Height of the physical page this table was extracted from, or {@link Float#NaN} when it is
	 * unknown. Used to decide whether a table sits close to the top/bottom edge of its page.
	 */
	public float getPageHeight() { return pageHeight; }
	public void setPageHeight(float pageHeight) { this.pageHeight = pageHeight; }

	public String getExtractionMethod() { return extractionMethod; }

	public void add(RectangularTextContainer chunk, int row, int col) {
		this.merge(chunk);
		
		rowCount = Math.max(rowCount, row + 1);
		colCount = Math.max(colCount, col + 1);
		
		CellPosition cp = new CellPosition(row, col);
		
		RectangularTextContainer old = cells.get(cp);
		if (old != null) chunk.merge(old);
		cells.put(cp, chunk);

		this.memoizedRows = null;
	}

	private List<List<RectangularTextContainer>> memoizedRows = null;

	/** Drops the cached row view after a cell is replaced directly. */
	void invalidateRows() {
		this.memoizedRows = null;
	}

	public List<List<RectangularTextContainer>> getRows() {
		if (this.memoizedRows == null) this.memoizedRows = computeRows();
		return this.memoizedRows;
	}

	private List<List<RectangularTextContainer>> computeRows() {
		List<List<RectangularTextContainer>> rows = new ArrayList<>();
		for (int i = 0; i < rowCount; i++) {
			List<RectangularTextContainer> lastRow = new ArrayList<>();
			rows.add(lastRow);
			for (int j = 0; j < colCount; j++) {
				RectangularTextContainer cell = cells.get(new CellPosition(i,j)); // JAVA_8 use getOrDefault()
				lastRow.add(cell != null ? cell : TextChunk.EMPTY);
			}
		}
		return rows;
	}
	
	public RectangularTextContainer getCell(int i, int j) {
		RectangularTextContainer cell = cells.get(new CellPosition(i,j)); // JAVA_8 use getOrDefault()
		return cell != null ? cell : TextChunk.EMPTY;
	}

}

class CellPosition implements Comparable<CellPosition> {

	CellPosition(int row, int col) {
		this.row = row;
		this.col = col;
	}

	final int row, col;

	@Override public int hashCode() {
		return row + 101 * col;
	}

	@Override public boolean equals(Object obj) {
		if (this == obj) return true;
		if (obj == null) return false;
		if (getClass() != obj.getClass()) return false;
		CellPosition other = (CellPosition) obj;
		return row == other.row && col == other.col;
	}

	@Override public int compareTo(CellPosition other) {
		int rowdiff = row - other.row;
		return rowdiff != 0 ? rowdiff : col - other.col;
	}

}
