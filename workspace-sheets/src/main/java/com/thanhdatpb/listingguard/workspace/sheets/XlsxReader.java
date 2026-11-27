package com.thanhdatpb.listingguard.workspace.sheets;

import com.thanhdatpb.listingguard.workspace.sheets.SheetRejected.Reason;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.EmptyFileException;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException;
import org.apache.poi.openxml4j.exceptions.OLE2NotOfficeXmlFileException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationshipTypes;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/**
 * Reads .xlsx and .xlsm with POI's event (SAX) model, so a large sheet is never held as a workbook object tree (ADR-059). A stored
 * formula result is read as it was saved and never recalculated; the macro project of an .xlsm is a package part this class never
 * opens, so no macro can run. POI's zip-bomb guard is set from the {@link SheetLimits} before every read. The guard is process-wide
 * static state of POI, so reads are serialized.
 */
final class XlsxReader {

    private static final Object LOCK = new Object();
    private static final String VBA_PROJECT = "application/vnd.ms-office.vbaProject";
    private static final long IN_MEMORY_BYTES = 1L << 20;

    private XlsxReader() {
    }

    /** Thrown to stop the parser once the preview has what it needs. */
    private static final class Enough extends RuntimeException {
        Enough() {
            super(null, null, false, false);
        }
    }

    static WorkbookInfo inspect(byte[] bytes, SheetLimits limits) {
        return withPackage(bytes, limits, (pkg, format) -> {
            List<WorkbookInfo.SheetInfo> sheets = new ArrayList<>();
            XSSFReader reader = new XSSFReader(pkg);
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg, false);
            StylesTable styles = reader.getStylesTable();
            XSSFReader.SheetIterator iterator = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (iterator.hasNext()) {
                try (InputStream sheet = iterator.next()) {
                    sheets.add(new WorkbookInfo.SheetInfo(iterator.getSheetName(),
                            parse(sheet, styles, strings, limits, SheetReader.PREVIEW_ROWS)));
                }
            }
            if (sheets.isEmpty()) {
                throw new SheetRejected(Reason.EMPTY, "The workbook has no sheet.");
            }
            return new WorkbookInfo(format, !pkg.getPartsByContentType(VBA_PROJECT).isEmpty() || format == SheetFormat.XLSM,
                    sheets);
        });
    }

    static SheetGrid read(byte[] bytes, String sheetName, SheetLimits limits) {
        return withPackage(bytes, limits, (pkg, format) -> {
            XSSFReader reader = new XSSFReader(pkg);
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg, false);
            StylesTable styles = reader.getStylesTable();
            XSSFReader.SheetIterator iterator = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (iterator.hasNext()) {
                try (InputStream sheet = iterator.next()) {
                    if (iterator.getSheetName().equals(sheetName)) {
                        return parse(sheet, styles, strings, limits, 0).withName(sheetName);
                    }
                }
            }
            throw new SheetRejected(Reason.SHEET_NOT_FOUND, "The workbook has no sheet with this name.");
        });
    }

    private interface PackageWork<T> {
        T run(OPCPackage pkg, SheetFormat format) throws Exception;
    }

    private static <T> T withPackage(byte[] bytes, SheetLimits limits, PackageWork<T> work) {
        synchronized (LOCK) {
            ZipSecureFile.setMinInflateRatio(limits.minInflateRatio());
            ZipSecureFile.setMaxEntrySize(limits.maxEntrySize());
            ZipSecureFile.setMaxTextSize(limits.maxTextSize());
            Path temp = null;
            try {
                OPCPackage pkg;
                if (bytes.length <= IN_MEMORY_BYTES) {
                    pkg = OPCPackage.open(new ByteArrayInputStream(bytes));
                } else {
                    // a large file is opened by random access on a private temporary file, so its entries are not copied into memory
                    temp = Files.createTempFile("lg-sheet-", ".zip",
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                    Files.write(temp, bytes);
                    pkg = OPCPackage.open(temp.toFile(), PackageAccess.READ);
                }
                try {
                    return work.run(pkg, formatOf(pkg));
                } finally {
                    pkg.revert();
                }
            } catch (SheetRejected e) {
                throw e;
            } catch (Exception | StackOverflowError e) {
                throw map(e);
            } finally {
                if (temp != null) {
                    try {
                        Files.deleteIfExists(temp);
                    } catch (IOException ignored) {
                        // a private file in the temporary folder; the operating system removes it
                    }
                }
            }
        }
    }

    private static SheetFormat formatOf(OPCPackage pkg) {
        var parts = pkg.getPartsByRelationshipType(PackageRelationshipTypes.CORE_DOCUMENT);
        for (PackagePart part : parts) {
            if (part.getContentType().toLowerCase(Locale.ROOT).contains("macroenabled")) {
                return SheetFormat.XLSM;
            }
        }
        return SheetFormat.XLSX;
    }

    static SheetRejected map(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SheetRejected rejected) {
                return rejected;
            }
            String message = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("zip bomb") || message.contains("potentially malicious") || message.contains("max size of the expanded")
                    || message.contains("exceeds the maximum") || message.contains("maximum number of") || message.contains("max. ratio")) {
                return new SheetRejected(Reason.ZIP_BOMB, "The file expands far more than its size allows.", e);
            }
            if (message.contains("encrypted") || message.contains("password")) {
                return new SheetRejected(Reason.ENCRYPTED, "The file is encrypted or protected by a password.", e);
            }
        }
        if (e instanceof EmptyFileException) {
            return new SheetRejected(Reason.EMPTY, "The file is empty.", e);
        }
        if (e instanceof OLE2NotOfficeXmlFileException) {
            return new SheetRejected(Reason.UNSUPPORTED, "A binary Office file is not read; save it as .xlsx.", e);
        }
        if (e instanceof NotOfficeXmlFileException || e instanceof InvalidFormatException || e instanceof POIXMLException
                || e instanceof IOException || e instanceof SAXException) {
            return new SheetRejected(Reason.CORRUPT, "The workbook cannot be read.", e);
        }
        return new SheetRejected(Reason.CORRUPT, "The workbook cannot be read.", e);
    }

    private static final class Rows implements SheetContentsHandler {
        private final SheetLimits limits;
        private final int stopAfter;
        final List<List<String>> rows = new ArrayList<>();
        private List<String> current;
        private int currentRow;

        Rows(SheetLimits limits, int stopAfter) {
            this.limits = limits;
            this.stopAfter = stopAfter;
        }

        @Override
        public void startRow(int rowNum) {
            currentRow = rowNum;
            current = new ArrayList<>();
        }

        @Override
        public void endRow(int rowNum) {
            int last = current.size();
            while (last > 0 && current.get(last - 1).isEmpty()) {
                last--;
            }
            if (last == 0) {
                return;
            }
            if (currentRow >= limits.maxRows()) {
                throw new SheetRejected(Reason.TOO_MANY_ROWS, "The sheet has content below row " + limits.maxRows() + ".");
            }
            if (stopAfter > 0 && currentRow >= stopAfter) {
                throw new Enough();
            }
            while (rows.size() < currentRow) {
                rows.add(List.of());
            }
            rows.add(List.copyOf(current.subList(0, last)));
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            if (cellReference == null) {
                return;
            }
            int column = CellReference.convertColStringToIndex(new CellReference(cellReference).getCellRefParts()[2]);
            String value = formattedValue == null ? "" : formattedValue;
            if (column >= limits.maxColumns()) {
                if (!value.isEmpty()) {
                    throw new SheetRejected(Reason.TOO_MANY_COLUMNS, "A row is wider than " + limits.maxColumns() + " columns.");
                }
                return;
            }
            while (current.size() < column) {
                current.add("");
            }
            if (current.size() == column) {
                current.add(value);
            }
        }
    }

    private static SheetGrid parse(InputStream sheet, StylesTable styles, ReadOnlySharedStringsTable strings, SheetLimits limits,
                                   int stopAfterRows) throws IOException, SAXException {
        Rows handler = new Rows(limits, stopAfterRows);
        try {
            XMLReader parser = XMLHelper.newXMLReader();
            // formulasNotResults = false: the cell's saved result, never the formula text and never a recalculation
            parser.setContentHandler(new XSSFSheetXMLHandler(styles, null, strings, handler, new DataFormatter(Locale.US), false));
            parser.parse(new InputSource(sheet));
        } catch (Enough stop) {
            // the preview is complete
        } catch (SAXException e) {
            if (e.getException() instanceof Enough) {
                return new SheetGrid("", handler.rows);
            }
            if (e.getException() instanceof SheetRejected rejected) {
                throw rejected;
            }
            throw e;
        } catch (javax.xml.parsers.ParserConfigurationException e) {
            throw new IOException(e);
        }
        return new SheetGrid("", handler.rows);
    }
}
