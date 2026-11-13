package com.thanhdatpb.listingguard.api.console.bulk;

import com.thanhdatpb.listingguard.api.console.editor.EditorMarketplace;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What a column of a pasted table can be (ADR-029): the fields of the standard input of a marketplace ({@code docs/input-schema.md}),
 * a SKU that names the row, and the three things a variation family needs. The labels are what the page shows; the {@code names}
 * are the headers the page recognizes (compared after {@link #normalize}), so a guess is a lookup in a list and never a judgment of
 * what a column "probably" holds. A column that matches nothing stays "skip".
 *
 * <p>Nothing here is a marketplace limit. A key that is a listing field is cut to the standard input by
 * {@code StandardListingAdapter.toStandardInput} before any check, so a wrong key could not reach a rule.
 */
final class BulkTargets {

    /** How a target is read. */
    enum Kind {
        /** names the row in the answer; not a listing field */
        SKU,
        /** one column, text */
        TEXT,
        /** several columns, or one column cut at a separator */
        LIST,
        /** one column is text, several are a list (Amazon {@code search_terms}) */
        TEXT_OR_LIST,
        PRICE,
        INTEGER,
        /** the SKU of the parent a row belongs to; rows with the same value are one variation family */
        PARENT_SKU,
        /** Amazon: the variation theme of the family, read from the parent row or the first child that has it */
        THEME,
        /** a variation attribute; the attribute name is the header of the column */
        ATTRIBUTE
    }

    record Target(String key, String label, Kind kind, String defaultSeparator, Set<String> names) {

        /** True when several columns may be given the same target. */
        boolean multiple() {
            return kind == Kind.LIST || kind == Kind.TEXT_OR_LIST || kind == Kind.ATTRIBUTE;
        }

        /** True for the targets that become a field of the listing JSON. */
        boolean listingField() {
            return switch (kind) {
                case TEXT, LIST, TEXT_OR_LIST, PRICE, INTEGER -> true;
                default -> false;
            };
        }

        /** True when one column given to this target may be cut at a separator the seller picks. */
        boolean separable() {
            return kind == Kind.LIST;
        }
    }

    record Separator(String key, String label, String text) {
    }

    static final String SKIP = "";
    static final String ATTRIBUTE = "attribute";

    static final List<Separator> SEPARATORS = List.of(
            new Separator("newline", "xuống dòng", "\n"),
            new Separator("comma", "dấu phẩy ( , )", ","),
            new Separator("semicolon", "dấu chấm phẩy ( ; )", ";"),
            new Separator("pipe", "dấu gạch đứng ( | )", "|"));

    private static final List<Target> ETSY = List.of(
            target("sku", "SKU / mã (đặt tên cho dòng)", Kind.SKU, null, "sku", "masku", "ma", "mahang", "masanpham", "id", "sellersku"),
            target("title", "Tiêu đề", Kind.TEXT, null, "title", "tieude", "ten", "tensanpham", "listingtitle", "name"),
            target("description", "Mô tả", Kind.TEXT, null, "description", "mota", "motasanpham", "desc", "noidung"),
            target("tags", "Tag (nhiều cột = nhiều tag)", Kind.LIST, "comma", "tags", "tag", "thetag", "the", "nhan", "tukhoa",
                    "keywords", "keyword"),
            target("materials", "Chất liệu", Kind.LIST, "comma", "materials", "material", "chatlieu"),
            target("price", "Giá", Kind.PRICE, null, "price", "gia", "dongia", "giaban"),
            target("quantity", "Số lượng", Kind.INTEGER, null, "quantity", "qty", "soluong", "stock", "tonkho"),
            target("category", "Danh mục", Kind.TEXT, null, "category", "danhmuc"),
            target("who_made", "Ai làm (who_made)", Kind.TEXT, null, "whomade", "ailam"),
            target("when_made", "Khi nào làm (when_made)", Kind.TEXT, null, "whenmade", "khinaolam"),
            target("parent_sku", "SKU cha (nhóm biến thể)", Kind.PARENT_SKU, null, "parentsku", "skucha", "parent", "macha"),
            target(ATTRIBUTE, "Thuộc tính biến thể (tên = tên cột)", Kind.ATTRIBUTE, null, "color", "colour", "mau", "mausac",
                    "size", "kichco", "kichthuoc", "style", "kieu"));

    private static final List<Target> AMAZON = List.of(
            target("sku", "SKU / mã (đặt tên cho dòng)", Kind.SKU, null, "sku", "masku", "ma", "mahang", "masanpham", "id",
                    "sellersku", "itemsku"),
            target("title", "Tiêu đề", Kind.TEXT, null, "title", "tieude", "ten", "tensanpham", "itemname", "productname", "name"),
            target("brand", "Thương hiệu", Kind.TEXT, null, "brand", "thuonghieu", "brandname"),
            target("bullet_points", "Bullet point (nhiều cột = nhiều bullet)", Kind.LIST, "newline", "bullet", "bullets",
                    "bulletpoint", "bulletpoints", "diemnoibat", "ychinh"),
            target("description", "Mô tả", Kind.TEXT, null, "description", "mota", "motasanpham", "desc", "noidung",
                    "productdescription"),
            target("search_terms", "Từ khóa tìm kiếm (search terms)", Kind.TEXT_OR_LIST, null, "searchterms", "searchterm",
                    "tukhoatimkiem", "generickeywords", "tukhoa", "keywords", "keyword"),
            target("product_type", "Loại sản phẩm (product type)", Kind.TEXT, null, "producttype", "loaisanpham", "feedproducttype"),
            target("price", "Giá", Kind.PRICE, null, "price", "gia", "dongia", "giaban", "standardprice"),
            target("quantity", "Số lượng", Kind.INTEGER, null, "quantity", "qty", "soluong", "stock", "tonkho"),
            target("item_highlights", "Item highlights", Kind.TEXT, null, "itemhighlights"),
            target("parent_sku", "SKU cha (nhóm biến thể)", Kind.PARENT_SKU, null, "parentsku", "skucha", "parent", "macha"),
            target("variation_theme", "Variation theme (dòng cha)", Kind.THEME, null, "variationtheme", "theme"),
            target(ATTRIBUTE, "Thuộc tính biến thể (tên = tên cột)", Kind.ATTRIBUTE, null, "color", "colour", "mau", "mausac",
                    "size", "kichco", "kichthuoc", "style", "kieu"));

    private BulkTargets() {
    }

    private static Target target(String key, String label, Kind kind, String separator, String... names) {
        return new Target(key, label, kind, separator, Set.of(names));
    }

    static List<Target> of(EditorMarketplace marketplace) {
        return marketplace.isAmazon() ? AMAZON : ETSY;
    }

    static Optional<Target> find(EditorMarketplace marketplace, String key) {
        return of(marketplace).stream().filter(target -> target.key().equals(key)).findFirst();
    }

    static Optional<Separator> separator(String key) {
        return SEPARATORS.stream().filter(separator -> separator.key().equals(key)).findFirst();
    }

    /**
     * The header lower-cased, stripped of accents and of everything but letters and digits: "Tiêu đề" and "tieu_de" are the same
     * word, "Bullet Point 1" is "bulletpoint1".
     */
    static String normalize(String header) {
        String decomposed = Normalizer.normalize(header.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (c == 'đ') {
                out.append('d');
            } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * The target a header names, if the list knows it: an exact name, or, for a target that takes several columns, the name with its
     * trailing number removed ({@code Tag 3}, {@code bullet_point5}). Empty when nothing matches; the caller then leaves the column out.
     */
    static Optional<Target> guess(EditorMarketplace marketplace, String header) {
        String name = normalize(header);
        if (name.isEmpty()) {
            return Optional.empty();
        }
        for (Target target : of(marketplace)) {
            if (target.names().contains(name)) {
                return Optional.of(target);
            }
        }
        String stem = name.replaceAll("\\d+$", "");
        if (!stem.isEmpty() && !stem.equals(name)) {
            for (Target target : of(marketplace)) {
                if (target.multiple() && target.names().contains(stem)) {
                    return Optional.of(target);
                }
            }
        }
        return Optional.empty();
    }
}
