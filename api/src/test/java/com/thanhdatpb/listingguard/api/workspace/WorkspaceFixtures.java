package com.thanhdatpb.listingguard.api.workspace;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * The synthetic files of the workspace tests, built with POI (ADR-059). Nothing here was read from a real Amazon template: the sheet names,
 * the settings row, the label row and the field names are invented to look like a flat file (a settings row, a label row, the field-name row,
 * parents and children, a second sheet), so every test says "not verified against a real file".
 */
final class WorkspaceFixtures {

    static final List<String> FIELDS = List.of("item_sku", "feed_product_type", "item_name", "brand_name", "parentage", "parent_sku",
            "variation_theme", "bullet_point1", "bullet_point2", "generic_keywords", "product_description", "size_name", "color_name",
            "standard_price", "quantity", "x_custom_flag");

    static final List<String> SETTINGS = List.of("TemplateType=synthetic", "Version=0.0", "Settings=none");

    /** The three rows above the data, exactly as the fixture writes them. */
    static List<List<String>> headerRows() {
        return List.of(SETTINGS, labels(), FIELDS);
    }

    private static List<String> labels() {
        return List.of("Seller SKU", "Product Type", "Item Name", "Brand", "Parentage", "Parent SKU", "Variation Theme", "Bullet 1", "Bullet 2",
                "Search terms", "Description", "Size", "Color", "Price", "Quantity", "Custom flag");
    }

    /** A parent with two children, a single listing and a second parent with one child: five SKUs per "family" prefix. */
    static List<List<String>> dataRows(String prefix) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of(prefix + "P1", "SHIRT", "Synthetic shirt", "Nobrand", "parent", "", "SizeColor", "", "", "", "A synthetic shirt", "", "", "", "", ""));
        rows.add(List.of(prefix + "P1-S", "SHIRT", "Synthetic shirt S", "Nobrand", "child", prefix + "P1", "SizeColor", "Soft", "Warm", "gift", "", "S", "Blue", "19.99", "5", "yes"));
        rows.add(List.of(prefix + "P1-M", "SHIRT", "Synthetic shirt M", "Nobrand", "child", prefix + "P1", "SizeColor", "Soft", "Warm", "gift", "", "M", "Blue", "19.99", "7", "yes"));
        rows.add(List.of(prefix + "S2", "MUG", "Synthetic mug", "Nobrand", "", "", "", "Ceramic", "", "cup", "A synthetic mug", "", "", "9.5", "3", ""));
        return rows;
    }

    static byte[] flatFile(String prefix) throws IOException {
        return flatFile(prefix, FIELDS, dataRows(prefix));
    }

    static byte[] flatFile(String prefix, List<String> fields, List<List<String>> data) throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            Sheet instructions = book.createSheet("Instructions");
            instructions.createRow(0).createCell(0).setCellValue("Read me");
            Sheet template = book.createSheet("Template");
            write(template, 0, SETTINGS);
            write(template, 1, fields.equals(FIELDS) ? labels() : fields.stream().map(f -> "Label of " + f).toList());
            write(template, 2, fields);
            int row = 3;
            for (List<String> cells : data) {
                write(template, row++, cells);
            }
            Sheet values = book.createSheet("Valid Values");
            write(values, 0, List.of("SHIRT", "MUG"));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            book.write(out);
            return out.toByteArray();
        }
    }

    static byte[] customizeFile(List<List<String>> data) throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            Sheet sheet = book.createSheet("Custom");
            write(sheet, 0, List.of("Custom settings"));
            write(sheet, 1, List.of("item_sku", "surface_name", "text_input_label"));
            int row = 2;
            for (List<String> cells : data) {
                write(sheet, row++, cells);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            book.write(out);
            return out.toByteArray();
        }
    }

    /** The same flat file as TSV text: the three rows above the data, then the data. */
    static byte[] flatFileTsv(String prefix) {
        StringBuilder text = new StringBuilder();
        headerRows().forEach(row -> text.append(String.join("\t", row)).append('\n'));
        dataRows(prefix).forEach(row -> text.append(String.join("\t", row)).append('\n'));
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void write(Sheet sheet, int index, List<String> cells) {
        Row row = sheet.createRow(index);
        for (int i = 0; i < cells.size(); i++) {
            if (!cells.get(i).isEmpty()) {
                row.createCell(i).setCellValue(cells.get(i));
            }
        }
    }

    /** The same package as an .xlsm: macro-enabled main part, an inert vbaProject part and its relationship. */
    static byte[] asMacroEnabled(byte[] xlsx) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(xlsx)); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                if (entry.getName().equals("[Content_Types].xml")) {
                    content = content.replace("spreadsheetml.sheet.main+xml", "ms-excel.sheet.macroEnabled.main+xml")
                            .replace("</Types>", "<Override PartName=\"/xl/vbaProject.bin\" ContentType=\"application/vnd.ms-office.vbaProject\"/></Types>");
                } else if (entry.getName().equals("xl/_rels/workbook.xml.rels")) {
                    content = content.replace("</Relationships>", "<Relationship Id=\"rIdVba\" Type=\"http://schemas.microsoft.com/office/2006/relationships/vbaProject\" Target=\"vbaProject.bin\"/></Relationships>");
                }
                put(zip, entry.getName(), content.getBytes(StandardCharsets.UTF_8));
            }
            put(zip, "xl/vbaProject.bin", "NOT A REAL MACRO PROJECT".getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    /** The package with a comment of {@code size} repeated letters in its Template sheet: about 1000:1 once zipped. */
    static byte[] zipBomb(byte[] xlsx, int size) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(xlsx)); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                byte[] content = in.readAllBytes();
                if (entry.getName().equals("xl/worksheets/sheet2.xml")) {
                    String xml = new String(content, StandardCharsets.UTF_8);
                    int end = xml.lastIndexOf("</worksheet>");
                    byte[] pad = new byte[size];
                    java.util.Arrays.fill(pad, (byte) 'x');
                    ByteArrayOutputStream grown = new ByteArrayOutputStream();
                    grown.write(xml.substring(0, end).getBytes(StandardCharsets.UTF_8));
                    grown.write("<!--".getBytes(StandardCharsets.UTF_8));
                    grown.write(pad);
                    grown.write(("-->" + "</worksheet>").getBytes(StandardCharsets.UTF_8));
                    content = grown.toByteArray();
                }
                put(zip, entry.getName(), content);
            }
        }
        return out.toByteArray();
    }

    /** What Excel writes for a password-protected workbook: an OLE container whose payload is the stream EncryptedPackage. */
    static byte[] passwordProtected() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            fs.createDocument(new ByteArrayInputStream(new byte[] {1, 2, 3}), "EncryptedPackage");
            fs.createDocument(new ByteArrayInputStream(new byte[] {4, 5, 6}), "EncryptionInfo");
            fs.writeFilesystem(out);
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }
}
