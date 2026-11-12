package com.thanhdatpb.listingguard.trademark.catalog.uspto;

import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoArchiveException.Kind;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Reads a USPTO bulk-data zip (ADR-009, PR A2d) <b>without extracting it</b>: each {@code .xml} entry is handed to the
 * converter as a {@link UsptoCatalogConverter.Source} that streams it out of the archive, so the disk holds the compressed
 * file only and no entry is ever written anywhere.
 *
 * <p>Every archive is checked before an entry is read: at most {@link Limits#maxEntries()} entries, no duplicate name, no
 * entry name that is absolute, has a drive letter, a {@code ..} segment, a backslash or a control character (nothing is
 * written, so such a name could not escape a folder here; it is refused so a crafted archive never passes for a USPTO file),
 * no entry whose declared size is over {@link Limits#maxEntryBytes()} or whose declared ratio is over
 * {@link Limits#maxRatio()}, and at least one {@code .xml} entry. The stream of an entry enforces the same size and ratio
 * on the bytes it really produces, whatever the header says, and compares the CRC-32 and the size at the end.
 * {@link #inspect} reads every XML entry once to its end, so a damaged archive is found before it is used.
 *
 * <p>Only {@code java.util.zip.ZipFile} is used (the central directory, not the local headers); an encrypted entry cannot
 * be read by it and is refused.
 */
public final class UsptoArchive {

    /** The ratio check never refuses an entry that produces less than this, so a tiny entry is not refused for a header. */
    static final long RATIO_FLOOR_BYTES = 1L << 20;

    private static final int BUFFER_BYTES = 64 * 1024;

    /**
     * @param maxEntries    the most entries, directories included
     * @param maxEntryBytes the most bytes one entry may produce
     * @param maxRatio      the most bytes one entry may produce per compressed byte
     */
    public record Limits(int maxEntries, long maxEntryBytes, long maxRatio) {
        public Limits {
            if (maxEntries < 1 || maxEntryBytes < 1 || maxRatio < 1) {
                throw new IllegalArgumentException("Archive limits must be at least 1.");
            }
        }

        /** 16 entries, 64 GiB an entry, 200 to 1. */
        public static Limits defaults() {
            return new Limits(16, 64L << 30, 200);
        }
    }

    /**
     * One XML entry.
     *
     * @param size           the declared uncompressed size, -1 when the archive does not say
     * @param compressedSize the declared compressed size, -1 when the archive does not say
     */
    public record Entry(String name, long size, long compressedSize, long crc) {
    }

    /**
     * An archive read to its end.
     *
     * @param xmlEntries       the XML entries, in the order of the archive
     * @param otherEntries     entries that are not XML (directories, notes); they are not read
     * @param uncompressedBytes the bytes the XML entries produced
     */
    public record Inspection(List<Entry> xmlEntries, int otherEntries, long uncompressedBytes) {
        public Inspection {
            xmlEntries = List.copyOf(xmlEntries);
        }
    }

    private record Listing(List<Entry> xml, int others) {
    }

    private UsptoArchive() {
    }

    /** True when the file name ends with {@code .zip}, whatever the case. */
    public static boolean isArchive(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    /**
     * Checks the archive and reads every XML entry once to its end (size, ratio, CRC-32).
     *
     * @throws UsptoArchiveException when it is refused; {@link UsptoArchiveException#unsafe()} says whether a new copy
     *                               could help
     * @throws IOException           when the file cannot be read
     */
    public static Inspection inspect(Path archive, Limits limits) throws IOException {
        Listing listing = list(archive, limits);
        long total = 0;
        byte[] buffer = new byte[BUFFER_BYTES];
        for (Entry entry : listing.xml()) {
            try (InputStream in = open(archive, entry.name(), limits)) {
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    total += n;
                }
            }
        }
        return new Inspection(listing.xml(), listing.others(), total);
    }

    /**
     * The XML entries of the archive as converter sources, named {@code <name>!<entry>}. The archive is checked now and
     * each entry again when it is opened; each {@link UsptoCatalogConverter.Source#open()} opens the archive anew.
     *
     * @param name the name of the archive in messages and in the converter's order (for example its path in the store)
     */
    public static List<UsptoCatalogConverter.Source> sources(Path archive, String name, Limits limits) throws IOException {
        List<UsptoCatalogConverter.Source> sources = new ArrayList<>();
        for (Entry entry : list(archive, limits).xml()) {
            String sourceName = name + "!" + entry.name();
            sources.add(new UsptoCatalogConverter.Source() {
                @Override
                public String name() {
                    return sourceName;
                }

                @Override
                public InputStream open() throws IOException {
                    return UsptoArchive.open(archive, entry.name(), limits);
                }
            });
        }
        return sources;
    }

    // --- the central directory ---

    private static Listing list(Path archive, Limits limits) throws IOException {
        String file = archive.getFileName().toString();
        try (ZipFile zip = openZip(archive)) {
            List<Entry> xml = new ArrayList<>();
            Set<String> names = new HashSet<>();
            int count = 0;
            int others = 0;
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                ZipEntry entry = entries.nextElement();
                count++;
                if (count > limits.maxEntries()) {
                    throw new UsptoArchiveException(Kind.UNSAFE, file + " has more than " + limits.maxEntries()
                            + " entries.");
                }
                String name = entry.getName();
                String problem = nameProblem(name);
                if (problem != null) {
                    throw new UsptoArchiveException(Kind.UNSAFE, file + " has an entry whose name " + problem
                            + "; it is not a USPTO archive this job reads.");
                }
                if (!names.add(name)) {
                    throw new UsptoArchiveException(Kind.UNSAFE, file + " has two entries named " + printable(name) + ".");
                }
                if (entry.isDirectory()) {
                    others++;
                    continue;
                }
                checkDeclared(file, entry, limits);
                if (name.toLowerCase(Locale.ROOT).endsWith(".xml")) {
                    xml.add(new Entry(name, entry.getSize(), entry.getCompressedSize(), entry.getCrc()));
                } else {
                    others++;
                }
            }
            if (xml.isEmpty()) {
                throw new UsptoArchiveException(Kind.UNSAFE, file + " holds no .xml entry.");
            }
            return new Listing(xml, others);
        }
    }

    private static ZipFile openZip(Path archive) throws IOException {
        try {
            return new ZipFile(archive.toFile());
        } catch (ZipException e) {
            throw new UsptoArchiveException(Kind.DAMAGED, archive.getFileName() + " is not a readable zip archive ("
                    + e.getMessage() + ").", e);
        }
    }

    /** Why an entry name is refused, or null. */
    static String nameProblem(String name) {
        if (name == null || name.isEmpty()) {
            return "is empty";
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return "has a control character";
            }
        }
        if (name.indexOf('\\') >= 0) {
            return "has a backslash";
        }
        if (name.startsWith("/")) {
            return "is an absolute path";
        }
        if (name.length() >= 2 && name.charAt(1) == ':' && Character.isLetter(name.charAt(0))) {
            return "has a drive letter";
        }
        for (String segment : name.split("/", -1)) {
            if (segment.equals("..")) {
                return "has a '..' segment";
            }
        }
        return null;
    }

    private static void checkDeclared(String file, ZipEntry entry, Limits limits) throws UsptoArchiveException {
        long size = entry.getSize();
        long compressed = entry.getCompressedSize();
        if (size > limits.maxEntryBytes()) {
            throw new UsptoArchiveException(Kind.UNSAFE, file + ": the entry " + printable(entry.getName()) + " declares "
                    + size + " bytes, over the limit of " + limits.maxEntryBytes() + ".");
        }
        if (size > RATIO_FLOOR_BYTES && compressed >= 0 && size > ratioLimit(compressed, limits)) {
            throw new UsptoArchiveException(Kind.UNSAFE, file + ": the entry " + printable(entry.getName())
                    + " declares " + size + " bytes from " + compressed + " compressed, more than " + limits.maxRatio()
                    + " to 1.");
        }
    }

    private static long ratioLimit(long compressed, Limits limits) {
        long base = Math.max(compressed, 1);
        return base > Long.MAX_VALUE / limits.maxRatio() ? Long.MAX_VALUE : base * limits.maxRatio();
    }

    private static String printable(String name) {
        StringBuilder text = new StringBuilder("'");
        name.codePoints().limit(120).forEach(c -> {
            if (c < 0x20 || c == 0x7F) {
                text.append(String.format("\\u%04x", c));
            } else {
                text.appendCodePoint(c);
            }
        });
        return text.append('\'').toString();
    }

    // --- one entry ---

    private static InputStream open(Path archive, String entryName, Limits limits) throws IOException {
        String file = archive.getFileName().toString();
        ZipFile zip = openZip(archive);
        try {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null || entry.isDirectory()) {
                throw new UsptoArchiveException(Kind.DAMAGED, file + ": the entry " + printable(entryName)
                        + " is no longer there.");
            }
            checkDeclared(file, entry, limits);
            InputStream raw;
            try {
                raw = zip.getInputStream(entry);
            } catch (ZipException e) {
                throw new UsptoArchiveException(Kind.DAMAGED, file + ": the entry " + printable(entryName)
                        + " cannot be read (" + e.getMessage() + ").", e);
            }
            return new Guarded(raw, zip, file, entry, limits);
        } catch (IOException | RuntimeException e) {
            zip.close();
            throw e;
        }
    }

    /**
     * The bytes of one entry, counted: more than the size limit or the ratio limit stops the read as unsafe; at the end the
     * CRC-32 and the declared size are compared. Closing it closes the archive.
     */
    private static final class Guarded extends FilterInputStream {
        private final ZipFile zip;
        private final String file;
        private final String entryName;
        private final long declaredSize;
        private final long declaredCrc;
        private final long limit;
        private final long ratioLimit;
        private final CRC32 crc = new CRC32();
        private long count;
        private boolean ended;

        Guarded(InputStream in, ZipFile zip, String file, ZipEntry entry, Limits limits) {
            super(in);
            this.zip = zip;
            this.file = file;
            this.entryName = entry.getName();
            this.declaredSize = entry.getSize();
            this.declaredCrc = entry.getCrc();
            this.limit = limits.maxEntryBytes();
            long compressed = entry.getCompressedSize();
            this.ratioLimit = compressed < 0 ? Long.MAX_VALUE
                    : Math.max(RATIO_FLOOR_BYTES, ratioLimit(compressed, limits));
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n;
            try {
                n = in.read(buffer, offset, length);
            } catch (ZipException e) {
                throw new UsptoArchiveException(Kind.DAMAGED, file + ": the entry " + printable(entryName)
                        + " is damaged (" + e.getMessage() + ").", e);
            } catch (java.io.EOFException e) {
                throw new UsptoArchiveException(Kind.DAMAGED, file + ": the entry " + printable(entryName)
                        + " ends early.", e);
            }
            if (n < 0) {
                end();
                return n;
            }
            count += n;
            if (count > limit) {
                throw new UsptoArchiveException(Kind.UNSAFE, file + ": the entry " + printable(entryName)
                        + " produces more than " + limit + " bytes.");
            }
            if (count > ratioLimit) {
                throw new UsptoArchiveException(Kind.UNSAFE, file + ": the entry " + printable(entryName)
                        + " produces more than its compressed size allows (" + ratioLimit + " bytes).");
            }
            crc.update(buffer, offset, n);
            return n;
        }

        private void end() throws UsptoArchiveException {
            if (ended) {
                return;
            }
            ended = true;
            if (declaredSize >= 0 && count != declaredSize) {
                throw new UsptoArchiveException(Kind.DAMAGED, file + ": the entry " + printable(entryName) + " gave "
                        + count + " bytes, its header says " + declaredSize + ".");
            }
            if (declaredCrc >= 0 && crc.getValue() != declaredCrc) {
                throw new UsptoArchiveException(Kind.DAMAGED, file + ": the CRC-32 of the entry " + printable(entryName)
                        + " differs from its header.");
            }
        }

        @Override
        public long skip(long n) throws IOException {
            // read instead of skipping, so every byte is counted and checked
            byte[] buffer = new byte[(int) Math.min(BUFFER_BYTES, Math.max(n, 1))];
            long skipped = 0;
            while (skipped < n) {
                int r = read(buffer, 0, (int) Math.min(buffer.length, n - skipped));
                if (r < 0) {
                    break;
                }
                skipped += r;
            }
            return skipped;
        }

        @Override
        public long transferTo(OutputStream out) throws IOException {
            byte[] buffer = new byte[BUFFER_BYTES];
            long total = 0;
            int n;
            while ((n = read(buffer, 0, buffer.length)) >= 0) {
                out.write(buffer, 0, n);
                total += n;
            }
            return total;
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                zip.close();
            }
        }
    }
}
