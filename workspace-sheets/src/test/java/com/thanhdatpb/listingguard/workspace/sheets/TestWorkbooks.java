package com.thanhdatpb.listingguard.workspace.sheets;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Synthetic workbooks for the tests: invented SKUs and titles, built with POI, nothing read from a real template. */
final class TestWorkbooks {

    static final List<String> FIELDS = List.of("item_sku", "feed_product_type", "item_name", "brand_name", "parent_child", "parent_sku",
            "variation_theme", "bullet_point1", "bullet_point2", "generic_keywords", "product_description", "size_name", "color_name",
            "standard_price", "quantity", "x_custom_flag");

    private TestWorkbooks() {
    }

    /** A flat file the way Amazon ships one: a settings row, a label row, the field-name row, then data; plus two other sheets. */
    static byte[] flatFile() throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            Sheet instructions = book.createSheet("Instructions");
            instructions.createRow(0).createCell(0).setCellValue("Read me");
            Sheet template = book.createSheet("Template");
            row(template, 0, "TemplateType=synthetic", "Version=0.0", "Settings=none");
            row(template, 1, "Seller SKU", "Product Type", "Item Name", "Brand", "Parentage", "Parent SKU", "Variation Theme", "Bullet 1",
                    "Bullet 2", "Search terms", "Description", "Size", "Color", "Price", "Quantity", "Custom flag");
            row(template, 2, FIELDS.toArray(String[]::new));
            row(template, 3, "P-1", "SHIRT", "Synthetic shirt", "Nobrand", "parent", "", "SizeColor", "", "", "", "A synthetic shirt", "", "", "", "", "");
            row(template, 4, "P-1-S", "SHIRT", "Synthetic shirt S", "Nobrand", "child", "P-1", "SizeColor", "Soft", "Warm", "gift", "", "S", "Blue", "19.99", "5", "yes");
            row(template, 5, "P-1-M", "SHIRT", "Synthetic shirt M", "Nobrand", "child", "P-1", "SizeColor", "Soft", "Warm", "gift", "", "M", "Blue", "19.99", "7", "yes");
            row(template, 6, "S-2", "MUG", "Synthetic mug", "Nobrand", "", "", "", "Ceramic", "", "cup", "A synthetic mug", "", "", "9.5", "3", "");
            Sheet values = book.createSheet("Valid Values");
            row(values, 0, "SHIRT", "MUG");
            return write(book);
        }
    }

    static byte[] flatFileWithFormula() throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            Sheet sheet = book.createSheet("Template");
            row(sheet, 0, "a_one", "b_two", "c_three");
            Cell cell = sheet.createRow(1).createCell(0);
            cell.setCellFormula("1+1");
            ((XSSFCell) cell).getCTCell().setV("99");
            return write(book);
        }
    }

    static byte[] wide(int columns, int rows) throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            Sheet sheet = book.createSheet("Template");
            for (int r = 0; r < rows; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < columns; c++) {
                    row.createCell(c).setCellValue("v" + r + "_" + c);
                }
            }
            return write(book);
        }
    }

    private static void row(Sheet sheet, int index, String... cells) {
        Row row = sheet.createRow(index);
        for (int i = 0; i < cells.length; i++) {
            row.createCell(i).setCellValue(cells[i]);
        }
    }

    static byte[] write(XSSFWorkbook book) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        book.write(out);
        return out.toByteArray();
    }

    /** The same package turned into an .xlsm: a macro-enabled main part, a (fake, inert) vbaProject part and its relationship. */
    static byte[] asMacroEnabled(byte[] xlsx) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(xlsx)); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                if (entry.getName().equals("[Content_Types].xml")) {
                    content = content.replace("spreadsheetml.sheet.main+xml", "ms-excel.sheet.macroEnabled.main+xml")
                            .replace("</Types>", "<Override PartName=\"/xl/vbaProject.bin\" ContentType=\"application/vnd.ms-office.vbaProject\"/></Types>");
                } else if (entry.getName().equals("xl/_rels/workbook.xml.rels")) {
                    content = content.replace("</Relationships>",
                            "<Relationship Id=\"rIdVba\" Type=\"http://schemas.microsoft.com/office/2006/relationships/vbaProject\" Target=\"vbaProject.bin\"/></Relationships>");
                }
                put(zip, entry.getName(), content.getBytes(StandardCharsets.UTF_8));
            }
            put(zip, "xl/vbaProject.bin", "NOT A REAL MACRO PROJECT".getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    /** The same package with {@code padding} bytes of XML comment appended to its first sheet. */
    static byte[] withSheetPadding(byte[] xlsx, byte[] padding) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(xlsx)); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                byte[] content = in.readAllBytes();
                if (entry.getName().equals("xl/worksheets/sheet2.xml") || entry.getName().equals("xl/worksheets/sheet1.xml")) {
                    String xml = new String(content, StandardCharsets.UTF_8);
                    int end = xml.lastIndexOf("</worksheet>");
                    ByteArrayOutputStream grown = new ByteArrayOutputStream();
                    grown.write(xml.substring(0, end).getBytes(StandardCharsets.UTF_8));
                    grown.write("<!--".getBytes(StandardCharsets.UTF_8));
                    grown.write(padding);
                    grown.write("-->".getBytes(StandardCharsets.UTF_8));
                    grown.write("</worksheet>".getBytes(StandardCharsets.UTF_8));
                    content = grown.toByteArray();
                }
                put(zip, entry.getName(), content);
            }
        }
        return out.toByteArray();
    }

    static byte[] repeated(int size, byte value) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }

    /** Letters that deflate poorly (so the ratio guard stays quiet) and are legal inside an XML comment. */
    static byte[] noise(int size) {
        byte[] bytes = new byte[size];
        Random random = new Random(7);
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) ('a' + random.nextInt(26));
        }
        return bytes;
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }
}
