package technology.tabula;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;
import technology.tabula.writers.CSVWriter;

public class TestPageTableMerger {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final String[] HEADER = {"Order No", "Item", "Qty"};

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - //

    private static Cell cell(float top, float left, float width, float height, String text) {
        Cell cell = new Cell(top, left, width, height);
        TextElement element = new TextElement(top, left, width, height, null, 10f, text, 2f);
        cell.setTextElements(Collections.singletonList(new TextChunk(element)));
        return cell;
    }

    private static Table table(int page, float pageHeight, float top, float bottom, float left,
                               float right, String[]... rows) {
        Table table = new Table(new SpreadsheetExtractionAlgorithm());
        table.setPageNumber(page);
        table.setPageHeight(pageHeight);

        float rowHeight = (bottom - top) / rows.length;
        float colWidth = (right - left) / rows[0].length;
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < rows[r].length; c++) {
                String text = rows[r][c];
                if (text == null) {
                    continue;
                }
                table.add(cell(top + r * rowHeight, left + c * colWidth, colWidth, rowHeight, text), r, c);
            }
        }
        return table;
    }

    private static Table firstPageTable(String[]... rows) {
        // Page is 800pt tall; the table ends 2pt above the bottom edge.
        return table(1, 800f, 100f, 798f, 40f, 540f, rows);
    }

    private static Table secondPageTable(String[]... rows) {
        // Page is 800pt tall; the table starts 2pt below the top edge.
        return table(2, 800f, 2f, 402f, 40f, 540f, rows);
    }

    private static List<String> csvRows(List<Table> tables) throws IOException {
        StringBuilder out = new StringBuilder();
        new CSVWriter().write(out, tables);
        List<String> rows = new ArrayList<>();
        for (String line : out.toString().split("\\r\\n|\\n")) {
            if (!line.isEmpty()) {
                rows.add(line);
            }
        }
        return rows;
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - //

    @Test
    public void mergesContinuationAndDropsRepeatedHeader() throws IOException {
        Table page1 = firstPageTable(
                HEADER,
                new String[]{"A001", "Widget", "2"});
        Table page2 = secondPageTable(
                HEADER,
                new String[]{"A002", "Gadget", "5"});

        List<Table> merged = new PageTableMerger().mergeTables(Arrays.asList(page1, page2));

        assertEquals(1, merged.size());
        assertEquals(1, merged.get(0).getPageNumber());
        assertEquals(Arrays.asList(
                "Order No,Item,Qty",
                "A001,Widget,2",
                "A002,Gadget,5"), csvRows(merged));
    }

    @Test
    public void continuesLastRowWhenNextPageHasNoOrderNumber() throws IOException {
        Table page1 = firstPageTable(
                HEADER,
                new String[]{"A001", "Widget", "2"});
        Table page2 = secondPageTable(
                HEADER,
                new String[]{"", "continued part", ""},
                new String[]{"A002", "Gadget", "5"});

        List<Table> merged = new PageTableMerger().mergeTables(Arrays.asList(page1, page2));

        assertEquals(1, merged.size());
        assertEquals(Arrays.asList(
                "Order No,Item,Qty",
                "A001,\"Widget\rcontinued part\",2",
                "A002,Gadget,5"), csvRows(merged));
    }

    @Test
    public void doesNotJoinRowsWhenContinuationHasAnotherIdentifier() throws IOException {
        Table page1 = firstPageTable(HEADER, new String[]{"A001", "Widget", "2"});
        Table page2 = secondPageTable(HEADER,
                new String[]{"B-77", "different order", ""},
                new String[]{"A002", "Gadget", "5"});

        List<Table> merged = new PageTableMerger().mergeTables(Arrays.asList(page1, page2));

        assertEquals(1, merged.size());
        assertEquals(Arrays.asList(
                "Order No,Item,Qty",
                "A001,Widget,2",
                "B-77,different order,",
                "A002,Gadget,5"), csvRows(merged));
    }

    @Test
    public void doesNotJoinRowsWhenContinuationRowIsBlank() throws IOException {
        Table page1 = firstPageTable(HEADER, new String[]{"A001", "Widget", "2"});
        Table page2 = secondPageTable(HEADER,
                new String[]{"", "", ""},
                new String[]{"A002", "Gadget", "5"});

        List<Table> merged = new PageTableMerger().mergeTables(Arrays.asList(page1, page2));

        assertEquals(1, merged.size());
        assertEquals(Arrays.asList(
                "Order No,Item,Qty",
                "A001,Widget,2",
                "\"\",,",
                "A002,Gadget,5"), csvRows(merged));
    }

    @Test
    public void keepsTablesIndependentWhenPagesAreNotAdjacent() throws IOException {
        Table page1 = firstPageTable(HEADER, new String[]{"A001", "Widget", "2"});
        Table page3 = table(3, 800f, 2f, 402f, 40f, 540f, HEADER, new String[]{"A002", "Gadget", "5"});

        List<Table> merged = new PageTableMerger().mergeTables(Arrays.asList(page1, page3));

        assertEquals(2, merged.size());
        assertEquals(Arrays.asList("Order No,Item,Qty", "A001,Widget,2"), rowsOf(merged.get(0)));
        assertEquals(Arrays.asList("Order No,Item,Qty", "A002,Gadget,5"), rowsOf(merged.get(1)));
    }

    @Test
    public void keepsTablesIndependentWhenGeometryDoesNotMatchPageEdge() {
        Table page1 = table(1, 800f, 100f, 500f, 40f, 540f, HEADER, new String[]{"A001", "Widget", "2"});
        Table page2 = secondPageTable(HEADER, new String[]{"A002", "Gadget", "5"});

        assertEquals(2, new PageTableMerger().mergeTables(Arrays.asList(page1, page2)).size());
    }

    @Test
    public void keepsTablesIndependentWhenHeaderDiffers() {
        Table page1 = firstPageTable(HEADER, new String[]{"A001", "Widget", "2"});
        Table page2 = secondPageTable(new String[]{"Invoice", "Item", "Qty"},
                new String[]{"A002", "Gadget", "5"});

        assertEquals(2, new PageTableMerger().mergeTables(Arrays.asList(page1, page2)).size());
    }

    @Test
    public void keepsTablesIndependentWhenColumnsDiffer() {
        Table page1 = firstPageTable(HEADER, new String[]{"A001", "Widget", "2"});
        Table page2 = table(2, 800f, 2f, 402f, 40f, 600f, HEADER, new String[]{"A002", "Gadget", "5"});

        assertEquals(2, new PageTableMerger().mergeTables(Arrays.asList(page1, page2)).size());
    }

    @Test
    public void keepsTablesIndependentWhenMoreThanOneCandidateMatches() {
        Table page1a = firstPageTable(HEADER, new String[]{"A001", "Widget", "2"});
        Table page1b = firstPageTable(HEADER, new String[]{"A003", "Bolt", "9"});
        Table page2 = secondPageTable(HEADER, new String[]{"A002", "Gadget", "5"});

        List<Table> merged = new PageTableMerger().mergeTables(Arrays.asList(page1a, page1b, page2));
        assertEquals(3, merged.size());
    }

    @Test
    public void mergesAcrossThreePages() throws IOException {
        Table page1 = firstPageTable(HEADER, new String[]{"A001", "Widget", "2"});
        Table page2 = table(2, 800f, 2f, 798f, 40f, 540f, HEADER, new String[]{"A002", "Gadget", "5"});
        Table page3 = table(3, 800f, 2f, 402f, 40f, 540f, HEADER, new String[]{"A003", "Bolt", "7"});

        List<Table> merged = new PageTableMerger().mergeTables(Arrays.asList(page1, page2, page3));
        assertEquals(1, merged.size());
        assertEquals(Arrays.asList(
                "Order No,Item,Qty",
                "A001,Widget,2",
                "A002,Gadget,5",
                "A003,Bolt,7"), csvRows(merged));
    }

    @Test
    public void leavesNonLatticeTablesAlone() {
        Table page1 = firstPageTable(HEADER, new String[]{"A001", "Widget", "2"});
        Table page2 = secondPageTable(HEADER, new String[]{"A002", "Gadget", "5"});
        // Re-label both as stream-mode tables.
        Table stream1 = new Table(new technology.tabula.extractors.BasicExtractionAlgorithm());
        stream1.setPageNumber(page1.getPageNumber());
        stream1.setPageHeight(page1.getPageHeight());
        Table stream2 = new Table(new technology.tabula.extractors.BasicExtractionAlgorithm());
        stream2.setPageNumber(page2.getPageNumber());
        stream2.setPageHeight(page2.getPageHeight());

        assertEquals(2, new PageTableMerger().mergeTables(Arrays.asList(stream1, stream2)).size());
    }

    @Test
    public void doesNotMutateInputs() throws IOException {
        Table page1 = firstPageTable(HEADER, new String[]{"A001", "Widget", "2"});
        Table page2 = secondPageTable(HEADER, new String[]{"", "continued", ""});

        new PageTableMerger().mergeTables(Arrays.asList(page1, page2));

        assertEquals(Arrays.asList("Order No,Item,Qty", "A001,Widget,2"), rowsOf(page1));
        assertEquals(Arrays.asList("Order No,Item,Qty", ",continued,"), rowsOf(page2));
    }

    @Test
    public void plainTablesWithoutAnnotationStayIndependent() {
        Table page1 = table(1, 800f, 100f, 798f, 40f, 540f, HEADER, new String[]{"A001", "Widget", "2"});
        Table page2 = table(2, 800f, 2f, 402f, 40f, 540f, HEADER, new String[]{"A002", "Gadget", "5"});
        page1.setPageHeight(java.lang.Float.NaN);
        page2.setPageHeight(java.lang.Float.NaN);

        assertEquals(2, new PageTableMerger().mergeTables(Arrays.asList(page1, page2)).size());
    }

    @Test
    public void cliOptionIsAvailable() {
        assertTrue(CommandLineApp.buildOptions().hasLongOption("merge-page-breaks"));
        assertTrue(CommandLineApp.buildOptions().hasOption("m"));
    }

    @Test
    public void cliMergesRealLatticeTablesAcrossPages() throws Exception {
        File pdf = folder.newFile("page_break.pdf");
        writePageBreakPdf(pdf);

        List<String> withoutMerge = runCli(pdf, false);
        assertEquals(Arrays.asList(
                "Order No,Item,Qty",
                "A001,Widget,2",
                "A002,Gadget,5",
                "Order No,Item,Qty",
                "A003,Bolt,7"), withoutMerge);

        List<String> withMerge = runCli(pdf, true);
        assertEquals(Arrays.asList(
                "Order No,Item,Qty",
                "A001,Widget,2",
                "A002,Gadget,5",
                "A003,Bolt,7"), withMerge);
    }

    @Test
    public void cliJoinsSplitOrderRowWithEmbeddedLineBreak() throws Exception {
        File pdf = folder.newFile("page_break_split_row.pdf");
        writePdf(pdf,
                new String[][]{HEADER, {"A001", "Widget", "2"}, {"A002", "Gadget", "5"}}, 60f, 800f,
                new String[][]{HEADER, {"", "continued note", ""}, {"A003", "Bolt", "7"}}, 40f, 500f);

        List<String> merged = runCli(pdf, true);
        assertEquals(Arrays.asList(
                "Order No,Item,Qty",
                "A001,Widget,2",
                "A002,\"Gadget\rcontinued note\",5",
                "A003,Bolt,7"), merged);
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - //

    private List<String> runCli(File pdf, boolean merge) throws Exception {
        CommandLineParser parser = new DefaultParser();
        List<String> args = new ArrayList<>(Arrays.asList(pdf.getAbsolutePath(), "-l", "-p", "all"));
        if (merge) {
            args.add("--merge-page-breaks");
        }
        CommandLine cmd = parser.parse(CommandLineApp.buildOptions(), args.toArray(new String[0]));
        StringBuilder out = new StringBuilder();
        new CommandLineApp(out, cmd).extractTables(cmd);
        List<String> rows = new ArrayList<>();
        for (String line : out.toString().split("\\r\\n|\\n")) {
            if (!line.isEmpty()) {
                rows.add(line);
            }
        }
        return rows;
    }

    private static void writePageBreakPdf(File target) throws IOException {
        writePdf(target,
                new String[][]{HEADER, {"A001", "Widget", "2"}, {"A002", "Gadget", "5"}}, 60f, 800f,
                new String[][]{HEADER, {"A003", "Bolt", "7"}}, 40f, 500f);
    }

    /** Writes a two-page ruled PDF; coordinates are tabula-space (y grows downwards). */
    private static void writePdf(File target, String[][] page1, float top1, float bottom1,
                                 String[][] page2, float top2, float bottom2) throws IOException {
        try (PDDocument document = new PDDocument()) {
            writePage(document, page1, top1, bottom1);
            writePage(document, page2, top2, bottom2);
            document.save(target);
        }
    }

    private static void writePage(PDDocument document, String[][] rows, float tabulaTop,
                                  float tabulaBottom) throws IOException {
        float left = 60f, right = 535f;
        PDPage page = new PDPage(new PDRectangle(595f, 842f));
        document.addPage(page);

        float pdfHeight = 842f;
        float top = pdfHeight - tabulaTop;
        float bottom = pdfHeight - tabulaBottom;
        float rowHeight = (bottom - top) / rows.length;
        float columnWidth = (right - left) / rows[0].length;

        try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
            for (int r = 0; r <= rows.length; r++) {
                float y = top + r * rowHeight;
                cs.moveTo(left, y);
                cs.lineTo(right, y);
            }
            for (int c = 0; c <= rows[0].length; c++) {
                float x = left + c * columnWidth;
                cs.moveTo(x, top);
                cs.lineTo(x, bottom);
            }
            cs.stroke();

            PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int r = 0; r < rows.length; r++) {
                for (int c = 0; c < rows[r].length; c++) {
                    if (rows[r][c] == null || rows[r][c].isEmpty()) {
                        continue;
                    }
                    cs.beginText();
                    cs.setFont(font, 9);
                    cs.newLineAtOffset(left + c * columnWidth + 4, top + (r + 1) * rowHeight + 4);
                    cs.showText(rows[r][c]);
                    cs.endText();
                }
            }
        }
    }

    private static List<String> rowsOf(Table table) {
        List<String> rows = new ArrayList<>();
        for (List<RectangularTextContainer> row : table.getRows()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(row.get(i).getText());
            }
            rows.add(sb.toString());
        }
        return rows;
    }
}
